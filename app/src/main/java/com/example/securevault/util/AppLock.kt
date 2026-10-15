package com.example.securevault.util

/**
 * 打开系统照片选择器、文件选择器或导出时，应用会短暂进入后台。
 * 这时不应该触发自动上锁，否则选完文件回来就被锁上，结果也丢了。
 */
object AppLock {
    @Volatile private var externalActivity = false

    fun beginExternal() { externalActivity = true }

    /** 回到前台时调用：如果刚才是去了系统选择器，返回 true 并复位 */
    fun consumeExternal(): Boolean {
        val v = externalActivity
        externalActivity = false
        return v
    }
}