@file:OptIn(ExperimentalCoroutinesApi::class)

package com.zxhhyj.vap.decode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.selects.select

/**
 * One compositor permit whose wait object **is** the permit.
 * [acquireOrPark] uses [select] on the permit channel versus [requestPark],
 * so returning the slot cannot lose a side-channel wake.
 *
 * 合成槽只有一个许可，等待对象就是许可本身。[acquireOrPark] 在许可通道和
 * [requestPark] 上 [select]，还槽不再依赖另一根可能丢掉的叫醒信号。
 */
public class VapCompositorSlot {
    private val permit = Channel<Unit>(capacity = 1)
    private val park = Channel<Unit>(CONFLATED)

    init {
        reset()
    }

    public val available: Boolean
        get() = !permit.isEmpty

    public fun tryAcquire(): Boolean = permit.tryReceive().isSuccess

    public fun release() {
        permit.trySend(Unit)
    }

    /** Abort a waiter without granting the permit (pause / teardown). / 叫醒等待者但不发许可（暂停 / 拆除）。 */
    public fun requestPark() {
        park.trySend(Unit)
    }

    public fun reset() {
        while (permit.tryReceive().isSuccess) {
        }
        park.tryReceive()
        permit.trySend(Unit)
    }

    public suspend fun acquireOrPark(canWait: () -> Boolean): Boolean {
        while (true) {
            if (!canWait()) return false
            while (park.tryReceive().isSuccess) {
            }
            val won = select<Boolean> {
                permit.onReceive { true }
                park.onReceive { false }
            }
            if (won) return true
            if (!canWait()) return false
        }
    }
}
