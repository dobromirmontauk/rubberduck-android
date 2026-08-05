package com.montauk.voicecapture.ci

import org.junit.Assert.fail
import org.junit.Test

/**
 * Deliberately-failing test used once to prove CI goes red on breakage
 * (vn-edu.36 acceptance criteria). Lives only on the throwaway `ci-proof`
 * branch, never on main.
 */
class CiRedProofTest {
    @Test
    fun `deliberately fails to prove CI goes red`() {
        fail("vn-edu.36 ci-proof: intentional failure, this branch is deleted after the red run")
    }
}
