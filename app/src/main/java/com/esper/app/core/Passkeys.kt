package com.esper.app.core

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import java.io.IOException

/**
 * Passkey sign-up and sign-in through Credential Manager (docs/SERVER.md §6).
 *
 * Framework Credential Manager only, no `credentials-play-services-auth`: the
 * project has no Play Services, so passkeys need Android 14+.
 *
 * [context] must be an Activity context: Credential Manager shows its sheet over it.
 * Each call returns the new session token.
 */
object Passkeys {
    const val UNSUPPORTED =
        "Passkeys need Android 14 or newer and a passkey provider (e.g. Google Password Manager or Bitwarden)."

    suspend fun register(context: Context, server: ServerClient): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) throw IOException(UNSUPPORTED)
        val ceremony = server.registerOptions().getOrThrow()
        val response = try {
            CredentialManager.create(context)
                .createCredential(context, CreatePublicKeyCredentialRequest(ceremony.publicKeyJson))
        } catch (e: CreateCredentialCancellationException) {
            throw IOException("Passkey creation cancelled.", e)
        } catch (e: CreateCredentialNoCreateOptionException) {
            throw IOException(UNSUPPORTED, e)
        } catch (e: CreateCredentialUnsupportedException) {
            throw IOException(UNSUPPORTED, e)
        } catch (e: CreateCredentialException) {
            throw IOException("Couldn't create a passkey: ${e.errorMessage ?: e.type}", e)
        }
        val json = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
            ?: throw IOException("The passkey provider returned no passkey.")
        server.registerVerify(ceremony.id, json).getOrThrow()
    }

    suspend fun signIn(context: Context, server: ServerClient): Result<String> = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) throw IOException(UNSUPPORTED)
        val ceremony = server.loginOptions().getOrThrow()
        val response = try {
            CredentialManager.create(context).getCredential(
                context,
                GetCredentialRequest(listOf(GetPublicKeyCredentialOption(ceremony.publicKeyJson))),
            )
        } catch (e: GetCredentialCancellationException) {
            throw IOException("Sign-in cancelled.", e)
        } catch (e: NoCredentialException) {
            throw IOException("No Esper passkey found. Create an account first, or check your passkey provider is turned on.", e)
        } catch (e: GetCredentialUnsupportedException) {
            throw IOException(UNSUPPORTED, e)
        } catch (e: GetCredentialException) {
            throw IOException("Couldn't sign in with a passkey: ${e.errorMessage ?: e.type}", e)
        }
        val json = (response.credential as? PublicKeyCredential)?.authenticationResponseJson
            ?: throw IOException("The passkey provider returned no passkey.")
        server.loginVerify(ceremony.id, json).getOrThrow()
    }
}
