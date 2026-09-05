package com.tictac.io.authentication

/**
 * Carries no detail about the existing account. The message below is what the client
 * sees, so it deliberately says nothing beyond "this address is taken".
 */
class EmailAlreadyRegisteredException :
    RuntimeException("An account with this email address already exists")
