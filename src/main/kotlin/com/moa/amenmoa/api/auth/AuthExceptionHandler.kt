package com.moa.amenmoa.api.auth

import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [AuthController::class])
class AuthExceptionHandler {
    @ExceptionHandler(AccountException::class)
    fun accountError(exception: AccountException): ResponseEntity<AuthError> {
        val status = when (exception.failure) {
            AccountFailure.INVALID_EMAIL, AccountFailure.INVALID_NICKNAME, AccountFailure.INVALID_PASSWORD ->
                HttpStatus.BAD_REQUEST
            AccountFailure.EMAIL_TAKEN, AccountFailure.NICKNAME_TAKEN -> HttpStatus.CONFLICT
            AccountFailure.INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED
        }
        return error(status, exception.failure.name)
    }

    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun invalidRefreshToken(): ResponseEntity<AuthError> =
        error(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<AuthError> =
        error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST")

    @ExceptionHandler(RedisConnectionFailureException::class, QueryTimeoutException::class)
    fun temporarilyUnavailable(): ResponseEntity<AuthError> =
        error(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TEMPORARILY_UNAVAILABLE")

    private fun error(status: HttpStatus, code: String): ResponseEntity<AuthError> =
        ResponseEntity.status(status).body(AuthError(code))
}

data class AuthError(val code: String)
