package com.moa.amenmoa.api

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["amenmoa.auth.secret=test-only-signing-key-at-least-32-bytes"])
class AmenmoaApiApplicationTests {

	@Test
	fun contextLoads() {
	}

}
