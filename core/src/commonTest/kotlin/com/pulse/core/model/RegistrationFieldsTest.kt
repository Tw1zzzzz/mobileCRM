package com.pulse.core.model

import com.pulse.core.model.AuthFieldId.CONFIRM
import com.pulse.core.model.AuthFieldId.EMAIL
import com.pulse.core.model.AuthFieldId.FACEIT
import com.pulse.core.model.AuthFieldId.NAME
import com.pulse.core.model.AuthFieldId.PASSWORD
import com.pulse.core.model.AuthFieldId.TEAM_CODE
import com.pulse.core.model.AuthFieldId.TEAM_NAME
import kotlin.test.Test
import kotlin.test.assertEquals

/** The keyboard's «Далее» walks the visible registration fields in this order. */
class RegistrationFieldsTest {
    @Test fun registrationFieldOrder() {
        val solo = listOf(NAME, EMAIL, PASSWORD, CONFIRM, FACEIT)
        assertEquals(solo, RegistrationForm().fields())
        assertEquals(solo + TEAM_CODE, RegistrationForm(accessType = AccessType.TEAM).fields())
        assertEquals(solo + TEAM_NAME, RegistrationForm(accessType = AccessType.TEAM, teamRole = TeamRole.MANAGER).fields())
    }
}
