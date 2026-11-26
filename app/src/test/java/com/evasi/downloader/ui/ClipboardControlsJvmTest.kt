package com.evasi.downloader.ui

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w393dp-h851dp-port")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ClipboardControlsJvmTest : ClipboardControlsChecks()
