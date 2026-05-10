package com.communicationcard.game.web.storage

/**
 * 浏览器 localStorage 的最薄包装。Kotlin 1.9.24 wasmJs 不引入 kotlinx-browser
 * （要求 Kotlin 2.0+），所以 DOM API 走 @JsFun。
 *
 * 用法：
 *   LocalStorage.getString("nickname") ?: "玩家"
 *   LocalStorage.setString("nickname", "Alice")
 *
 * 失败（如隐私模式禁 localStorage）静默返回 null / 不抛。
 */
object LocalStorage {

    fun getString(key: String): String? {
        val raw = jsLocalStorageGet(key)
        return if (raw.isEmpty()) null else raw
    }

    fun setString(key: String, value: String) {
        jsLocalStorageSet(key, value)
    }

    fun remove(key: String) {
        jsLocalStorageRemove(key)
    }
}

@JsFun(
    """(key) => {
        try {
            const v = window.localStorage.getItem(key);
            return v == null ? '' : v;
        } catch (e) { return ''; }
    }""",
)
private external fun jsLocalStorageGet(key: String): String

@JsFun(
    """(key, value) => {
        try { window.localStorage.setItem(key, value); } catch (e) {}
    }""",
)
private external fun jsLocalStorageSet(key: String, value: String)

@JsFun(
    """(key) => {
        try { window.localStorage.removeItem(key); } catch (e) {}
    }""",
)
private external fun jsLocalStorageRemove(key: String)
