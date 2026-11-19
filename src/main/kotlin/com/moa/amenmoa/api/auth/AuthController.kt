package com.moa.amenmoa.api.auth

import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class AuthController(private val accounts: AccountService, private val tokens: AuthTokens) {
    @PostMapping("$AUTH_PATH/email-availability")
    fun emailAvailability(@RequestBody request: EmailRequest) =
        AvailabilityResponse(accounts.isEmailAvailable(request.email))

    @PostMapping(NICKNAME_AVAILABILITY_PATH)
    fun nicknameAvailability(@RequestBody request: NicknameRequest) =
        AvailabilityResponse(accounts.isNicknameAvailable(request.nickname))

    @PostMapping("$AUTH_PATH/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@RequestBody request: SignupRequest): AccountResponse =
        accounts.register(request.email, request.nickname, request.password).toResponse()

    @PostMapping("$AUTH_PATH/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<TokenResponse> {
        val account = accounts.authenticate(request.email, request.password)
        return tokenResponse(tokens.start(account.id))
    }

    @PostMapping("$AUTH_PATH/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<TokenResponse> =
        tokenResponse(tokens.refresh(request.refreshToken))

    @PostMapping("$AUTH_PATH/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal jwt: Jwt) {
        val sessionId = jwt.getClaimAsString("sid")
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw AccountException(AccountFailure.INVALID_CREDENTIALS)
        tokens.end(sessionId)
    }

    @GetMapping(CURRENT_ACCOUNT_PATH)
    fun me(@AuthenticationPrincipal jwt: Jwt): AccountResponse {
        val userId = jwt.subject?.toLongOrNull() ?: throw AccountException(AccountFailure.INVALID_CREDENTIALS)
        return accounts.findById(userId).toResponse()
    }

    // 응답 DTO를 따로 두면 엔티티에 새 필드를 추가해도 비밀번호 해시가 JSON에 섞이지 않습니다.
    private fun Account.toResponse() = AccountResponse(id, email, nickname)

    private fun tokenResponse(body: TokenResponse): ResponseEntity<TokenResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}

class EmailRequest(val email: String)
class NicknameRequest(val nickname: String)
class SignupRequest(val email: String, val nickname: String, val password: String)
class LoginRequest(val email: String, val password: String)
class RefreshRequest(val refreshToken: String)
data class AvailabilityResponse(val available: Boolean)
data class AccountResponse(val id: Long, val email: String, val nickname: String)
