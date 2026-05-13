package com.communicationcard.server.bootstrap

import com.communicationcard.server.gameModule
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PR 0 验收测试：保证骨架重构后 / 和 /health 仍可达，且 Application.module()
 * 提取成 `gameModule()` 扩展函数后能被 testApplication 装配。
 *
 * 后续 admin 模块（PR 1+）的路由也会通过同样的 testApplication 模式覆盖。
 */
class ApplicationBootstrapTest {

    @Test
    fun `root endpoint returns 200 OK`() = testApplication {
        application { gameModule(enableAdmin = false) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("OK", response.bodyAsText())
    }

    @Test
    fun `health endpoint returns 200 Healthy`() = testApplication {
        application { gameModule(enableAdmin = false) }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("Healthy", response.bodyAsText())
    }

    @Test
    fun `unknown route returns 404 not 500`() = testApplication {
        application { gameModule(enableAdmin = false) }
        val response = client.get("/this-route-does-not-exist")
        // 404 (而不是 500) 证明 StatusPages 装好后没把 NotFound 当 exception 吞掉
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
