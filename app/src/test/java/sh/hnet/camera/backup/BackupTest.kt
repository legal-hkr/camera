package sh.hnet.camera.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.hnet.camera.CameraSettings
import sh.hnet.camera.Grid
import sh.hnet.camera.MotionPhoto
import sh.hnet.camera.VolumeKeys
import sh.hnet.camera.capabilities.model.Aspect

class BackupTest {
    private val changed = CameraSettings(
        aspect = Aspect.SQUARE, ultraHdr = false, hdrVideo = false, location = true, grid = Grid.QUARTERS, level = true, videoUhd = false, videoFps = 60,
        timerFlash = true, volumeKeys = VolumeKeys.ZOOM, qrCodes = false, motionPhoto = MotionPhoto.ON, mirror = true, shutterSound = false, videoHevc = false,
    )

    private fun file(settings: BackupSettings) = BackupFile(appVersion = "0.1.0", appVersionCode = 1, createdAt = "2026-11-24T10:00:00Z", settings = settings)

    @Test fun roundTrip() {
        val text = Backup.encode(file(BackupSettings.of(changed)))
        val check = Backup.check(text) as BackupCheck.Valid
        assertEquals(changed, check.backup.settings.applyTo(CameraSettings()))
    }

    @Test fun missingAndUnknownValuesKeepTheCurrent() {
        val text = """{"format":"sh.hnet.camera.backup","formatVersion":1,"appVersion":"0.1.0","appVersionCode":1,"createdAt":"x","settings":{"grid":"HEXAGONS","videoFps":45,"level":true,"later":1}}"""
        val check = Backup.check(text) as BackupCheck.Valid
        val restored = check.backup.settings.applyTo(changed)
        assertEquals(Grid.QUARTERS, restored.grid)
        assertEquals(60, restored.videoFps)
        assertTrue(restored.level)
        assertEquals(changed.copy(level = true), restored)
    }

    @Test fun locationOnlyWithAccess() {
        assertFalse(BackupSettings(location = true).applyTo(CameraSettings(), locationAllowed = false).location)
        assertTrue(BackupSettings(location = true).applyTo(CameraSettings(), locationAllowed = true).location)
    }

    @Test fun otherFiles() {
        assertTrue(Backup.check("nope") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format":"sh.hnet.gallery.backup","formatVersion":1}""") is BackupCheck.NotOurs)
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format":"sh.hnet.camera.backup","formatVersion":2}"""))
        assertTrue(Backup.check("""{"format":"sh.hnet.camera.backup","formatVersion":{}}""") is BackupCheck.NotOurs)
    }
}
