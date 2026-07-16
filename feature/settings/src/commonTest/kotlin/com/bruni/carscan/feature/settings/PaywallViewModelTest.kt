package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.monetization.PurchaseKind
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

class PaywallViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `real store prices are loaded into state on open`() = runTest(dispatcher) {
        val prices = mapOf(
            PurchaseKind.SUB_MONTHLY to "₩2,500",
            PurchaseKind.SUB_YEARLY to "₩19,000",
            PurchaseKind.LIFETIME to "₩33,000",
        )
        val vm = PaywallViewModel(FakeBillingPort(prices = prices), FakeEntitlements())
        runCurrent()

        vm.state.value.prices shouldBe prices
    }

    @Test
    fun `an unavailable store leaves prices empty instead of crashing`() = runTest(dispatcher) {
        val vm = PaywallViewModel(FakeBillingPort(failPrices = true), FakeEntitlements(isPremium = true))
        runCurrent()

        vm.state.value.prices shouldBe emptyMap()
        // The premium state still resolves — the price failure is isolated.
        vm.state.value.isPremium shouldBe true
    }
}
