package com.yumark.app.core.util

sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class DatabaseError(message: String, cause: Throwable? = null) : AppError(message, cause)
    class FileSystemError(message: String, cause: Throwable? = null) : AppError(message, cause)
    class NetworkError(message: String, cause: Throwable? = null) : AppError(message, cause)
    class ValidationError(message: String) : AppError(message)
    class ExportError(message: String, cause: Throwable? = null) : AppError(message, cause)
    class UnknownError(cause: Throwable) : AppError("An unexpected error occurred", cause)
}

object ErrorHandler {
    fun handle(throwable: Throwable): AppError {
        return when (throwable) {
            is AppError -> throwable
            is java.io.IOException -> AppError.FileSystemError(
                "File operation failed: ${throwable.message}", throwable
            )
            is android.database.SQLException -> AppError.DatabaseError(
                "Database operation failed: ${throwable.message}", throwable
            )
            is IllegalArgumentException, is IllegalStateException -> AppError.ValidationError(
                throwable.message ?: "Validation failed"
            )
            else -> AppError.UnknownError(throwable)
        }
    }

    fun getUserMessage(error: AppError): String {
        return when (error) {
            is AppError.DatabaseError -> "Failed to access database. Please try again."
            is AppError.FileSystemError -> "Failed to read or write file. Check storage permissions."
            is AppError.NetworkError -> "Network error. Check your connection."
            is AppError.ValidationError -> error.message ?: "Invalid input"
            is AppError.ExportError -> "Failed to export document. Try a different format."
            is AppError.UnknownError -> "Something went wrong. Please try again."
        }
    }
}

fun <T> Result<T>.mapError(): Result<T> {
    return this.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(ErrorHandler.handle(it)) }
    )
}
