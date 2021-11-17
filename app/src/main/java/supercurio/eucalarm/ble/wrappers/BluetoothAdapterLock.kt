package supercurio.eucalarm.ble.wrappers

import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
class BluetoothAdapterLock @Inject constructor() {
    private var status = -1

    val lock = ReentrantLock()
    private val condition: Condition = lock.newCondition()

    fun awaitStatus(): Int {
        condition.await()
        return status
    }

    fun signalAll(status: Int) = lock.withLock {
        this.status = status
        condition.signalAll()
    }

    companion object {
        private const val TAG = "BluetoothAdapterLock"
    }
}
