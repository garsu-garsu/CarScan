package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.data.backup.BackupOutcome
import com.bruni.carscan.core.data.backup.BackupSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

private val EMPTY_SOURCE = BackupSource { _, _, _ -> -1 }

class BackupFlowTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(backup: FakeBackupService) = SettingsViewModel(FakeSettingsRepository(), backup)

    @Test
    fun `exporting asks for a password, then a file, then reports success`() = runTest(dispatcher) {
        val backup = FakeBackupService()
        val vm = vm(backup)

        vm.onIntent(SettingsIntent.StartBackup(BackupMode.EXPORT))
        vm.state.value.backup shouldBe BackupStep.Password(BackupMode.EXPORT)

        vm.onIntent(SettingsIntent.ConfirmBackupPassword("비밀번호1234"))
        vm.state.value.backup shouldBe BackupStep.Picking(BackupMode.EXPORT)

        vm.exportTo { }
        vm.state.value.backup shouldBe BackupStep.Done(BackupMode.EXPORT, BackupOutcome.OK)
        backup.exportedWith shouldBe "비밀번호1234"
    }

    @Test
    fun `restoring reports the wrong password rather than pretending it worked`() = runTest(dispatcher) {
        val backup = FakeBackupService(BackupOutcome.WRONG_PASSWORD)
        val vm = vm(backup)

        vm.onIntent(SettingsIntent.StartBackup(BackupMode.IMPORT))
        vm.onIntent(SettingsIntent.ConfirmBackupPassword("틀린암호1234"))
        vm.importFrom(EMPTY_SOURCE)

        vm.state.value.backup shouldBe BackupStep.Done(BackupMode.IMPORT, BackupOutcome.WRONG_PASSWORD)
        backup.importedWith shouldBe "틀린암호1234"
    }

    /** Backing out of the system file chooser is the only signal it sends, and it has to unstick the UI. */
    @Test
    fun `cancelling the file chooser leaves no dialog behind`() = runTest(dispatcher) {
        val vm = vm(FakeBackupService())

        vm.onIntent(SettingsIntent.StartBackup(BackupMode.IMPORT))
        vm.onIntent(SettingsIntent.ConfirmBackupPassword("비밀번호1234"))
        vm.onIntent(SettingsIntent.DismissBackup)

        vm.state.value.backup shouldBe null
    }

    /**
     * A file chooser that comes back after the user dismissed the flow — a late Activity result —
     * must not silently write a backup with a password that is no longer on screen.
     */
    @Test
    fun `a file that arrives after the flow was dismissed is ignored`() = runTest(dispatcher) {
        val backup = FakeBackupService()
        val vm = vm(backup)

        vm.onIntent(SettingsIntent.StartBackup(BackupMode.EXPORT))
        vm.onIntent(SettingsIntent.ConfirmBackupPassword("비밀번호1234"))
        vm.onIntent(SettingsIntent.DismissBackup)
        vm.exportTo { }

        backup.exportedWith shouldBe null
        vm.state.value.backup shouldBe null
    }

    /** Confirming a password that nothing asked for must not open a file chooser. */
    @Test
    fun `a password confirmed with no prompt open goes nowhere`() = runTest(dispatcher) {
        val vm = vm(FakeBackupService())
        vm.onIntent(SettingsIntent.ConfirmBackupPassword("비밀번호1234"))
        vm.state.value.backup shouldBe null
    }

    @Test
    fun `closing the result clears the flow`() = runTest(dispatcher) {
        val vm = vm(FakeBackupService())

        vm.onIntent(SettingsIntent.StartBackup(BackupMode.EXPORT))
        vm.onIntent(SettingsIntent.ConfirmBackupPassword("비밀번호1234"))
        vm.exportTo { }
        vm.onIntent(SettingsIntent.DismissBackup)

        vm.state.value.backup shouldBe null
    }
}
