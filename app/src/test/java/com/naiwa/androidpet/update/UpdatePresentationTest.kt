package com.naiwa.androidpet.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePresentationTest {
    @Test fun `settings states distinguish checking latest unavailable and failure`() {
        assertEquals("正在检查更新…", UpdatePresentation.message(CheckResult.Checking))
        assertEquals("已经是最新版本啦！", UpdatePresentation.message(CheckResult.UpToDate))
        assertTrue(UpdatePresentation.message(CheckResult.Unavailable("尚未发布")).contains("尚未发布"))
        assertTrue(UpdatePresentation.message(CheckResult.Failure("网络断开")).contains("网络断开"))
    }

    @Test fun `ignored version is hidden on home but still visible in manual settings`() {
        assertFalse(UpdatePresentation.showHomeCard(8, 8))
        assertTrue(UpdatePresentation.showHomeCard(9, 8))
        assertFalse(UpdatePresentation.showHomeCard(0, 0))
    }

    @Test fun `later hides only the chosen version until the reminder time`() {
        assertFalse(UpdatePresentation.showHomeCard(8, 0, 8, 1000, 999))
        assertTrue(UpdatePresentation.showHomeCard(8, 0, 8, 1000, 1000))
        assertTrue(UpdatePresentation.showHomeCard(9, 0, 8, 1000, 999))
    }

    @Test fun `completed status survives process recreation without inventing an unchecked result`() {
        val absent = CheckResult.Unavailable("尚未发布正式 Release")
        val saved = UpdateStatusCodec.encode(absent)
        assertEquals(absent, UpdateStatusCodec.decode(saved.first, saved.second, 123L))
        assertEquals(CheckResult.UpToDate, UpdateStatusCodec.decode("latest", "", 123L))
        assertTrue(UpdateStatusCodec.decode("", "", 123L) is CheckResult.Unavailable)
        assertEquals(CheckResult.Idle, UpdateStatusCodec.decode("", "", 0L))
    }
}
