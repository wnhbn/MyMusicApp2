package com.simplemobiletools.musicplayer.activities

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.RelativeLayout
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.viewpager.widget.ViewPager
import com.simplemobiletools.commons.dialogs.FilePickerDialog
import com.simplemobiletools.commons.dialogs.RadioGroupDialog
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.commons.models.FAQItem
import com.simplemobiletools.commons.models.RadioItem
import com.simplemobiletools.commons.models.Release
import com.simplemobiletools.musicplayer.BuildConfig
import com.simplemobiletools.musicplayer.R
import com.simplemobiletools.musicplayer.adapters.ViewPagerAdapter
import com.simplemobiletools.musicplayer.dialogs.NewPlaylistDialog
import com.simplemobiletools.musicplayer.dialogs.SleepTimerCustomDialog
import com.simplemobiletools.musicplayer.extensions.*
import com.simplemobiletools.musicplayer.fragments.MyViewPagerFragment
import com.simplemobiletools.musicplayer.helpers.*
import com.simplemobiletools.musicplayer.models.Events
import com.simplemobiletools.musicplayer.services.MusicService
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_albums.*
import kotlinx.android.synthetic.main.fragment_artists.*
import kotlinx.android.synthetic.main.fragment_folders.*
import kotlinx.android.synthetic.main.fragment_playlists.*
import kotlinx.android.synthetic.main.fragment_tracks.*
import kotlinx.android.synthetic.main.view_current_track_bar.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    private var isSearchOpen = false
    private var searchMenuItem: MenuItem? = null
    private var bus: EventBus? = null
    private var storedShowTabs = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        appLaunched(BuildConfig.APPLICATION_ID)
        storeStateVariables()

        handlePermission(PERMISSION_WRITE_STORAGE) {
            if (it) {
                initActivity()
            } else {
                toast(R.string.no_storage_permissions)
                finish()
            }
        }

        volumeControlStream = AudioManager.STREAM_MUSIC
        checkWhatsNewDialog()
        checkAppOnSDCard()

        if (config.appRunCount == 1) {
            config.wereTrackFoldersAdded = true
        } else if (config.appRunCount > 5) {
            // assume old users have it created already
            config.wasAllTracksPlaylistCreated = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            System.exit(0)
            return
        }

        updateTextColors(main_holder)
        sleep_timer_holder.background = ColorDrawable(getProperBackgroundColor())
        sleep_timer_stop.applyColorFilter(getProperTextColor())
        updateCurrentTrackBar()

        val adjustedPrimaryColor = getProperPrimaryColor()
        main_tabs_holder.apply {
            setTabTextColors(getProperTextColor(), adjustedPrimaryColor)
            setSelectedTabIndicatorColor(adjustedPrimaryColor)
        }

        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), adjustedPrimaryColor)
        }
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = viewpager.currentItem
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        setupSearch(menu)
        updateMenuItemColors(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.apply {
            findItem(R.id.create_new_playlist).isVisible = getCurrentFragment() == playlists_fragment_holder
            findItem(R.id.create_playlist_from_folder).isVisible = getCurrentFragment() == playlists_fragment_holder
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sort -> showSortingDialog()
            R.id.sleep_timer -> showSleepTimer()
            R.id.create_new_playlist -> createNewPlaylist()
            R.id.create_playlist_from_folder -> createPlaylistFromFolder()
            R.id.equalizer -> launchEqualizer()
            R.id.settings -> launchSettings()
            R.id.about -> launchAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun storeStateVariables() {
        config.apply {
            storedShowTabs = showTabs
        }
    }

    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(R.id.search)
        (searchMenuItem!!.actionView as SearchView).apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        getCurrentFragment()?.onSearchQueryChanged(newText)
                    }
                    return true
                }
            })
        }

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                getCurrentFragment()?.onSearchOpened()
                isSearchOpen = true
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                getCurrentFragment()?.onSearchClosed()
                isSearchOpen = false
                return true
            }
        })
    }

    private fun initActivity() {
        bus = EventBus.getDefault()
        bus!!.register(this)
        initFragments()
        sleep_timer_stop.setOnClickListener { stopSleepTimer() }

        current_track_bar.setOnClickListener {
            Intent(this, TrackActivity::class.java).apply {
                startActivity(this)
            }
        }

        if (MusicService.mCurrTrack == null) {
            ensureBackgroundThread {
                if (queueDAO.getAll().isNotEmpty()) {
                    sendIntent(INIT_QUEUE)
                }
            }
        }

        updateAllDatabases {
            getAllFragments().forEach {
                it?.setupFragment(this)
            }
        }
    }

    private fun initFragments() {
        viewpager.adapter = ViewPagerAdapter(this)
        viewpager.offscreenPageLimit = tabsList.size - 1
        viewpager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                if (isSearchOpen) {
                    searchMenuItem?.collapseActionView()
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                main_tabs_holder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
            }
        })

        main_tabs_holder.onTabSelectionChanged(
            tabSelectedAction = {
                viewpager.currentItem = it.position
            }
        )

        val tabLabels = arrayListOf(
            getString(R.string.playlists),
            getString(R.string.artists),
            getString(R.string.albums),
            getString(R.string.tracks)
        )

        if (isQPlus()) {
            tabLabels.add(1, getString(R.string.folders))
        }

        main_tabs_holder.removeAllTabs()
        var skippedTabs = 0
        tabsList.forEachIndexed { index, value ->
            if (config.showTabs and value == 0) {
                skippedTabs++
            } else {
                val label = tabLabels[index]
                val tab = main_tabs_holder.newTab().setText(label)
                tab.contentDescription = label
                main_tabs_holder.addTab(tab, index - skippedTabs, config.lastUsedViewPagerPage == index - skippedTabs)
            }
        }

        viewpager.currentItem = config.lastUsedViewPagerPage
    }

    private fun getCurrentFragment(): MyViewPagerFragment? {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment>()
        if (showTabs and TAB_PLAYLISTS != 0) {
            fragments.add(playlists_fragment_holder)
        }

        if (showTabs and TAB_FOLDERS != 0) {
            fragments.add(folders_fragment_holder)
        }

        if (showTabs and TAB_ARTISTS != 0) {
            fragments.add(artists_fragment_holder)
        }

        if (showTabs and TAB_ALBUMS != 0) {
            fragments.add(albums_fragment_holder)
        }

        if (showTabs and TAB_TRACKS != 0) {
            fragments.add(tracks_fragment_holder)
        }

        return fragments.getOrNull(viewpager.currentItem)
    }

    private fun showSortingDialog() {
        getCurrentFragment()?.onSortOpen(this)
    }

    private fun updateCurrentTrackBar() {
        current_track_bar.updateColors()
        current_track_bar.updateCurrentTrack(MusicService.mCurrTrack)
        current_track_bar.updateTrackState(MusicService.getIsPlaying())
        checkSleepTimerPosition()
    }

    private fun createNewPlaylist() {
        NewPlaylistDialog(this) {
            EventBus.getDefault().post(Events.PlaylistsUpdated())
        }
    }

    private fun createPlaylistFromFolder() {
        FilePickerDialog(this, pickFile = false) {
            createPlaylistFrom(it)
        }
    }

    private fun createPlaylistFrom(path: String) {
        ensureBackgroundThread {
            getFolderTracks(path, true) { tracks ->
                runOnUiThread {
                    NewPlaylistDialog(this) { playlistId ->
                        tracks.forEach {
                            it.playListId = playlistId
                        }

                        ensureBackgroundThread {
                            tracksDAO.insertAll(tracks)
                            EventBus.getDefault().post(Events.PlaylistsUpdated())
                        }
                    }
                }
            }
        }
    }

    private fun showSleepTimer() {
        val minutes = getString(R.string.minutes_raw)
        val hour = resources.getQuantityString(R.plurals.hours, 1, 1)

        val items = arrayListOf(
            RadioItem(5 * 60, "5 $minutes"),
            RadioItem(10 * 60, "10 $minutes"),
            RadioItem(20 * 60, "20 $minutes"),
            RadioItem(30 * 60, "30 $minutes"),
            RadioItem(60 * 60, hour)
        )

        if (items.none { it.id == config.lastSleepTimerSeconds }) {
            val lastSleepTimerMinutes = config.lastSleepTimerSeconds / 60
            val text = resources.getQuantityString(R.plurals.minutes, lastSleepTimerMinutes, lastSleepTimerMinutes)
            items.add(RadioItem(config.lastSleepTimerSeconds, text))
        }

        items.sortBy { it.id }
        items.add(RadioItem(-1, getString(R.string.custom)))

        RadioGroupDialog(this, items, config.lastSleepTimerSeconds) {
            if (it as Int == -1) {
                SleepTimerCustomDialog(this) {
                    if (it > 0) {
                        pickedSleepTimer(it)
                    }
                }
            } else if (it > 0) {
                pickedSleepTimer(it)
            }
        }
    }

    private fun pickedSleepTimer(seconds: Int) {
        config.lastSleepTimerSeconds = seconds
        config.sleepInTS = System.currentTimeMillis() + seconds * 1000
        startSleepTimer()
    }

    private fun startSleepTimer() {
        checkSleepTimerPosition()
        sleep_timer_holder.fadeIn()
        sendIntent(START_SLEEP_TIMER)
    }

    private fun stopSleepTimer() {
        sendIntent(STOP_SLEEP_TIMER)
        sleep_timer_holder.fadeOut()
    }

    private fun checkSleepTimerPosition() {
        if (current_track_bar.isVisible()) {
            (sleep_timer_holder.layoutParams as RelativeLayout.LayoutParams).removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        } else {
            (sleep_timer_holder.layoutParams as RelativeLayout.LayoutParams).addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
    }

    private fun getAllFragments() =
        arrayListOf(playlists_fragment_holder, folders_fragment_holder, artists_fragment_holder, albums_fragment_holder, tracks_fragment_holder)

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trackChangedEvent(event: Events.TrackChanged) {
        current_track_bar.updateCurrentTrack(event.track)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trackStateChanged(event: Events.TrackStateChanged) {
        current_track_bar.updateTrackState(event.isPlaying)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun noStoragePermission(event: Events.NoStoragePermission) {
        toast(R.string.no_storage_permissions)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun sleepTimerChanged(event: Events.SleepTimerChanged) {
        sleep_timer_value.text = event.seconds.getFormattedDuration()
        sleep_timer_holder.beVisible()

        if (event.seconds == 0) {
            finish()
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun playlistsUpdated(event: Events.PlaylistsUpdated) {
        playlists_fragment_holder?.setupFragment(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun tracksUpdated(event: Events.RefreshTracks) {
        tracks_fragment_holder?.setupFragment(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun trackDeleted(event: Events.TrackDeleted) {
        updateAllDatabases {
            getAllFragments().forEach {
                it?.setupFragment(this)
            }
        }
    }

    private fun launchEqualizer() {
        hideKeyboard()
        startActivity(Intent(applicationContext, EqualizerActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_EVENT_BUS or LICENSE_GLIDE

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
            FAQItem(R.string.faq_1_title_commons, R.string.faq_1_text_commons),
            FAQItem(R.string.faq_4_title_commons, R.string.faq_4_text_commons),
            FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons),
            FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons),
            FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
        )

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            add(Release(25, R.string.release_25))
            add(Release(27, R.string.release_27))
            add(Release(28, R.string.release_28))
            add(Release(37, R.string.release_37))
            add(Release(59, R.string.release_59))
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
