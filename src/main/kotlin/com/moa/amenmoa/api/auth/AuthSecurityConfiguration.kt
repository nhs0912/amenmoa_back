package com.moa.amenmoa.api.auth

import jakarta.servlet.DispatcherType
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain

internal const val AUTH_PATH = "/api/v1/auth"
internal const val NICKNAME_AVAILABILITY_PATH = "/api/v1/users/nickname-availability"
internal const val CURRENT_ACCOUNT_PATH = "/api/v1/me"

@ConfigurationProperties("amenmoa.auth")
class AuthProperties(val issuer: String, val audience: String, secret: String) {
    internal val signingKey: SecretKey

    init {
        val bytes = secret.toByteArray(Charsets.UTF_8)
        require(bytes.size >= 32) { "AUTH_JWT_SECRET must be at least 32 bytes" }
        signingKey = SecretKeySpec(bytes, "HmacSHA256")
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties::class)
class AuthSecurityConfiguration {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authJwtEncoder(properties: AuthProperties): JwtEncoder =
        NimbusJwtEncoder.withSecretKey(properties.signingKey).algorithm(MacAlgorithm.HS256).build()

    @Bean
    fun authJwtDecoder(properties: AuthProperties): JwtDecoder =
        NimbusJwtDecoder.withSecretKey(properties.signingKey).macAlgorithm(MacAlgorithm.HS256).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefaultWithIssuer(properties.issuer),
                    JwtAudienceValidator(properties.audience),
                ),
            )
        }

    @Bean
    fun apiSecurity(http: HttpSecurity, decoder: JwtDecoder): SecurityFilterChain =
        http
            // 쿠키를 사용하지 않는 API이므로 보호 경로는 Authorization 헤더의 Bearer 토큰으로 인증합니다.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .requestMatchers(
                    HttpMethod.POST,
                    "$AUTH_PATH/email-availability",
                    NICKNAME_AVAILABILITY_PATH,
                    "$AUTH_PATH/signup",
                    "$AUTH_PATH/login",
                    "$AUTH_PATH/refresh",
                ).permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { server -> server.jwt { it.decoder(decoder) } }
            .build()
}
