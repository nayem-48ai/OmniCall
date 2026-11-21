package com.example

import android.media.AudioManager
import android.telecom.DisconnectCause
import com.example.audio.AudioCodecUtils
import com.example.audio.RtpJitterBuffer
import com.example.data.preferences.AppSettingsState
import com.example.data.repository.BlocklistRepository
import com.example.data.repository.CallLogItem
import com.example.data.repository.ContactItem
import com.example.data.repository.ContactPhoneNumber
import com.example.data.repository.VcfContactsCodec
import com.example.domain.model.AvailableCallingEnvironment
import com.example.domain.model.CallDirection
import com.example.domain.model.CallSourceType
import com.example.domain.model.OutgoingRoutingMode
import com.example.domain.model.RecordingEngine
import com.example.domain.model.RecordingType
import com.example.domain.model.SimSlotInfo
import com.example.domain.model.SipAccountModel
import com.example.domain.model.SipBalancePreset
import com.example.domain.model.SipRegistrationStatus
import com.example.domain.model.SipTransport
import com.example.sip.BalanceResponseParser
import com.example.sip.SipBalanceFetcher
import com.example.sip.SipClientEngine
import com.example.sip.SipMessageCodec
import com.example.telecom.ActiveCallCoordinator
import com.example.telecom.CallNotificationManager
import com.example.telecom.IncomingRingerController
import com.example.telecom.MissedCallRecord
import com.example.telecom.RingerPolicy
import com.example.ui.theme.AppColorPalette
import com.example.ui.viewmodel.DialerViewModel
import com.example.ui.viewmodel.EnrichedCallLogItem
import com.example.ui.viewmodel.RecentsDateBucket
import com.example.ui.viewmodel.RecentsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SipAndRoutingUnitTest {

    @Test
    fun dynamicRoutingModes_dualSimAndSip_returnsFourModes() {
        val env = AvailableCallingEnvironment(
            simSlots = listOf(
                SimSlotInfo(0, 1, "SIM 1", "Carrier A", "+15550001"),
                SimSlotInfo(1, 2, "SIM 2", "Carrier B", "+15550002")
            ),
            sipAccounts = listOf(
                SipAccountModel(
                    id = 1,
                    displayName = "Office",
                    domain = "sip.example.com",
                    username = "1001",
                    port = 5061,
                    transport = SipTransport.TLS
                )
            )
        )
        val modes = env.availableModes()
        assertEquals(
            listOf(
                OutgoingRoutingMode.SIM_1,
                OutgoingRoutingMode.SIM_2,
                OutgoingRoutingMode.SIP,
                OutgoingRoutingMode.ASK_EVERY_TIME
            ),
            modes
        )
    }

    @Test
    fun dynamicRoutingModes_dualSimWithoutSip_returnsThreeModes() {
        val env = AvailableCallingEnvironment(
            simSlots = listOf(
                SimSlotInfo(0, 1, "SIM 1", "Carrier A", ""),
                SimSlotInfo(1, 2, "SIM 2", "Carrier B", "")
            ),
            sipAccounts = emptyList()
        )
        assertEquals(
            listOf(
                OutgoingRoutingMode.SIM_1,
                OutgoingRoutingMode.SIM_2,
                OutgoingRoutingMode.ASK_EVERY_TIME
            ),
            env.availableModes()
        )
    }

    @Test
    fun sipMessageCodec_parsesChallengeAndBuildsAuth() {
        val raw401 = "SIP/2.0 401 Unauthorized\r\n" +
            "Via: SIP/2.0/UDP 10.0.0.5:5060;branch=z9hG4bK-123\r\n" +
            "Call-ID: abc@sip.example.com\r\n" +
            "CSeq: 1 REGISTER\r\n" +
            "WWW-Authenticate: Digest realm=\"sip.example.com\", nonce=\"xyz987\", qop=\"auth\", algorithm=MD5\r\n" +
            "Content-Length: 0\r\n\r\n"

        val parsed = SipMessageCodec.parse(raw401)
        assertNotNull(parsed)
        assertEquals(401, parsed!!.statusCode)

        val challenge = SipMessageCodec.parseChallenge(parsed)
        assertNotNull(challenge)
        assertEquals("sip.example.com", challenge!!.realm)

        val (headerName, headerVal) = SipMessageCodec.buildDigestAuthorizationHeader(
            challenge = challenge,
            username = "1001",
            password = "secret",
            method = "REGISTER",
            uri = "sip:sip.example.com"
        )
        assertEquals("Authorization", headerName)
        assertTrue(headerVal.contains("response="))
        assertTrue(headerVal.contains("username=\"1001\""))
    }

    @Test
    fun sdpNegotiation_selectsPcmuMatchingRtpEngineAndParsesRemoteG722() {
        val sdp = SipMessageCodec.buildSdpOffer("192.168.1.50", 40002, preferWideband = true)
        val parsed = SipMessageCodec.parseSdp(sdp)
        assertNotNull(parsed)
        assertEquals("192.168.1.50", parsed!!.remoteIp)
        assertEquals(40002, parsed.remotePort)
        assertEquals("PCMU", parsed.codecName)
        assertEquals(0, parsed.payloadType)

        val remoteOnlyG722 = "v=0\r\no=- 1 1 IN IP4 203.0.113.5\r\ns=-\r\nc=IN IP4 203.0.113.5\r\nt=0 0\r\nm=audio 50000 RTP/AVP 9 101\r\na=rtpmap:9 G722/8000\r\na=rtpmap:101 telephone-event/8000\r\n"
        val parsedRemote = SipMessageCodec.parseSdp(remoteOnlyG722)
        assertNotNull(parsedRemote)
        assertEquals("G722", parsedRemote!!.codecName)
        assertEquals(9, parsedRemote.payloadType)
    }

    @Test
    fun g711MuLawRoundTrip_preservesSignalPolarity() {
        val pcm = shortArrayOf(0, 1000, -1000, 12000, -12000)
        val encoded = AudioCodecUtils.encodeMuLaw(pcm)
        val decoded = AudioCodecUtils.decodeMuLaw(encoded)
        assertEquals(pcm.size, decoded.size)
        assertTrue(decoded[1] > 0)
        assertTrue(decoded[2] < 0)
        assertTrue(decoded[3] > 8000)
        assertTrue(decoded[4] < -8000)
    }

    @Test
    fun balanceParser_extractsBalanceFromJsonAndIgnoresCredit() {
        val json = """{"status":"ok","credit":"500.00","balance":"124.50","currency":"BDT"}"""
        assertEquals("124.50 BDT", BalanceResponseParser.extractBalance(json))
    }

    @Test
    fun balanceParser_extractsBalanceFromHtmlAndIgnoresCredit() {
        val html = """
            <html><body>
              <table>
                <tr><th>Credit</th><td>999.00 BDT</td></tr>
                <tr><th>Balance</th><td>42.80 BDT</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        assertEquals("42.80 BDT", BalanceResponseParser.extractBalance(html))
    }

    @Test
    fun balanceParser_extractsSpanIdBalance() {
        val html = """<div class="card"><span class="credit">100.00</span><span id="user_balance">৳150.25</span></div>"""
        assertEquals("৳150.25", BalanceResponseParser.extractBalance(html))
    }

    @Test
    fun sipBalancePreset_resolvesIccUrlAndRecordingTypesExist() {
        val acc = SipAccountModel(
            displayName = "ICC",
            domain = "icctalk.com",
            username = "09639170537",
            port = 5060,
            transport = SipTransport.UDP,
            balancePreset = SipBalancePreset.ICC_COMMUNICATION
        )
        assertEquals("https://icctalk.com:2020/OTTSSO/login", acc.resolvedBalanceUrl())
        assertEquals(3, RecordingType.entries.size)
        assertEquals(3, RecordingEngine.entries.size)
    }

    @Test
    fun blocklistNormalizeKey_matchesLocalAndInternationalAndSip() {
        assertEquals(
            BlocklistRepository.normalizeKey("+8801712-345678"),
            BlocklistRepository.normalizeKey("01712345678")
        )
        assertEquals(
            "1002@sip.example.com",
            BlocklistRepository.normalizeKey("sip:1002@sip.example.com")
        )
    }

    @Test
    fun sipRegister_usesMappedPublicContactForIncomingInvites() {
        val req = SipMessageCodec.buildRegisterRequest(
            domain = "icctalk.com",
            username = "09639170537",
            localIp = "192.168.1.25",
            localPort = 45120,
            transport = SipTransport.UDP,
            callId = "reg-1@icctalk.com",
            cseq = 2,
            fromTag = "ftag1",
            branch = "z9hG4bK-reg",
            expiresSec = 120,
            contactIp = "103.45.67.89",
            contactPort = 61234
        )
        assertTrue(req.contains("Via: SIP/2.0/UDP 192.168.1.25:45120;rport;branch=z9hG4bK-reg"))
        assertTrue(req.contains("Contact: <sip:09639170537@103.45.67.89:61234;transport=udp>"))
        assertTrue(req.contains("Expires: 120"))
    }

    @Test
    fun sipMessageCodec_parsesCompactHeadersOnIncomingInvite() {
        val rawInvite = "INVITE sip:09639170537@103.45.67.89:61234 SIP/2.0\r\n" +
            "v: SIP/2.0/UDP 203.0.113.10:5060;branch=z9hG4bK-in1\r\n" +
            "f: \"Caller\" <sip:01712345678@icctalk.com>;tag=rem1\r\n" +
            "t: <sip:09639170537@icctalk.com>\r\n" +
            "i: call-in-99@icctalk.com\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "l: 0\r\n\r\n"

        val parsed = SipMessageCodec.parse(rawInvite)
        assertNotNull(parsed)
        assertTrue(parsed!!.isRequest)
        assertEquals("INVITE", parsed.method)
        assertEquals("call-in-99@icctalk.com", parsed.firstHeader("Call-ID"))
        assertTrue(parsed.firstHeader("From").orEmpty().contains("01712345678@icctalk.com"))
    }

    @Test
    fun sipInvite_keepsLocalViaAndUsesNatMappedContact() {
        val sdp = SipMessageCodec.buildSdpOffer("10.0.0.15", 40002, preferWideband = true)
        val invite = SipMessageCodec.buildInviteRequest(
            targetUri = "sip:01782961419@icctalk.com",
            domain = "icctalk.com",
            username = "09639170537",
            displayName = "ICC Communication",
            localIp = "10.0.0.15",
            localSipPort = 45120,
            transport = SipTransport.UDP,
            callId = "inv-1@icctalk.com",
            cseq = 12,
            fromTag = "ftag2",
            branch = "z9hG4bK-inv",
            sdpBody = sdp,
            contactIp = "103.45.67.89",
            contactPort = 61234
        )
        assertTrue(invite.contains("Via: SIP/2.0/UDP 10.0.0.15:45120;rport;branch=z9hG4bK-inv"))
        assertTrue(invite.contains("Contact: <sip:09639170537@103.45.67.89:61234;transport=udp>"))
        assertTrue(invite.contains("c=IN IP4 10.0.0.15"))
    }

    @Test
    fun sipPreDialSyntaxValidation_catchesInvalidConfigAndAcceptsValidSettings() {
        val validAccount = SipAccountModel(
            id = 1,
            displayName = "ICC",
            domain = "icctalk.com",
            proxyHost = "sbc.icctalk.com",
            username = "09639170537",
            port = 5060,
            transport = SipTransport.UDP,
            isEnabled = true
        )
        assertNull(SipClientEngine.validateAccountSettingsSyntax(validAccount, "01712345678"))
        assertEquals("sbc.icctalk.com", validAccount.effectiveProxyHost())

        val disabledError = SipClientEngine.validateAccountSettingsSyntax(
            validAccount.copy(isEnabled = false),
            "01712345678"
        )
        assertNotNull(disabledError)
        assertTrue(disabledError!!.contains("disabled"))

        val badPortError = SipClientEngine.validateAccountSettingsSyntax(
            validAccount.copy(port = 70000, transport = SipTransport.TCP),
            "01712345678"
        )
        assertNotNull(badPortError)
        assertTrue(badPortError!!.contains("TCP"))

        val badProxyError = SipClientEngine.validateAccountSettingsSyntax(
            validAccount.copy(proxyHost = "bad host/path"),
            "01712345678"
        )
        assertNotNull(badProxyError)
        assertTrue(badProxyError!!.contains("proxy"))

        val blankDestError = SipClientEngine.validateAccountSettingsSyntax(validAccount, "01712 345678")
        assertNotNull(blankDestError)
    }

    @Test
    fun sipRfc3261_ackCancelBranchReuseAndRecordRoutePreservation() {
        val fixedBranch = "z9hG4bK-fixed-invite-branch"
        val routeSet = listOf("<sip:edge1.icctalk.com:5060;lr>", "<sip:core.icctalk.com;lr>")
        val ack = SipMessageCodec.buildAckRequest(
            targetUri = "sip:01782961419@icctalk.com",
            fromHeader = "<sip:09639170537@icctalk.com>;tag=t1",
            toHeader = "<sip:01782961419@icctalk.com>;tag=t2",
            localIp = "10.0.0.15",
            localSipPort = 45120,
            transport = SipTransport.UDP,
            callId = "c-1@icctalk.com",
            cseq = 12,
            branch = fixedBranch,
            routeSet = routeSet
        )
        assertTrue(ack.contains("branch=$fixedBranch"))
        assertTrue(ack.contains("Route: <sip:edge1.icctalk.com:5060;lr>"))
        assertTrue(ack.contains("Route: <sip:core.icctalk.com;lr>"))

        val cancel = SipMessageCodec.buildSimpleInDialogRequest(
            method = "CANCEL",
            targetUri = "sip:01782961419@icctalk.com",
            fromHeader = "<sip:09639170537@icctalk.com>;tag=t1",
            toHeader = "<sip:01782961419@icctalk.com>",
            localIp = "10.0.0.15",
            localSipPort = 45120,
            transport = SipTransport.TLS,
            callId = "c-1@icctalk.com",
            cseq = 12,
            branch = fixedBranch
        )
        assertTrue(cancel.startsWith("CANCEL sip:01782961419@icctalk.com;transport=tls SIP/2.0"))
        assertTrue(cancel.contains("branch=$fixedBranch"))
        assertTrue(cancel.contains("CSeq: 12 CANCEL"))
    }

    @Test
    fun vcfContactsCodec_exportsAndParsesMultilineAndQuotedPrintableCards() {
        val sampleContacts = listOf(
            ContactItem(
                id = 1L,
                lookupKey = "k1",
                displayName = "Ayesha Rahman",
                isFavorite = true,
                phoneNumbers = listOf(
                    ContactPhoneNumber("+8801316658359", "Mobile", isDefault = true),
                    ContactPhoneNumber("01712345678", "Work", isDefault = false)
                ),
                photoUri = null
            ),
            ContactItem(
                id = 2L,
                lookupKey = "k2",
                displayName = "Ajrin Kona",
                isFavorite = false,
                phoneNumbers = listOf(
                    ContactPhoneNumber("+8801782961419", "Home", isDefault = false)
                ),
                photoUri = null
            )
        )

        val vcf = VcfContactsCodec.generateVcf(sampleContacts)
        assertTrue(vcf.contains("BEGIN:VCARD"))
        assertTrue(vcf.contains("FN:Ayesha Rahman"))
        assertTrue(vcf.contains("TEL;TYPE=CELL;TYPE=PREF:+8801316658359"))

        val parsedExported = VcfContactsCodec.parseVcf(vcf)
        assertEquals(2, parsedExported.size)
        assertEquals("Ayesha Rahman", parsedExported[0].displayName)
        assertEquals(2, parsedExported[0].phoneNumbers.size)
        assertEquals("Work", parsedExported[0].phoneNumbers[1].typeLabel)
        assertTrue(parsedExported[0].phoneNumbers[0].isDefault)

        // Verify vCard 2.1 QUOTED-PRINTABLE + line unfolding + EMAIL/ORG support
        val legacyVcf = """
            BEGIN:VCARD
            VERSION:2.1
            N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;=E0=A6=B0=E0=A6=B9=E0=A6=BF=
            =E0=A6=AE;;;
            FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=E0=A6=B0=E0=A6=B9=E0=A6=BF=
            =E0=A6=AE
            TEL;CELL;PREF:+8801911223344
            EMAIL:rahim@example.com
            ORG:Omni
             Labs
            END:VCARD
        """.trimIndent()
        val parsedLegacy = VcfContactsCodec.parseVcf(legacyVcf)
        assertEquals(1, parsedLegacy.size)
        assertEquals("রহিম", parsedLegacy[0].displayName)
        assertEquals("+8801911223344", parsedLegacy[0].phoneNumbers.first().number)
        assertEquals("Mobile", parsedLegacy[0].phoneNumbers.first().typeLabel)
        assertEquals("rahim@example.com", parsedLegacy[0].email)
        assertEquals("OmniLabs", parsedLegacy[0].organization)
    }

    @Test
    fun sipClientEngine_buildDialFallbacks_normalizesE164AndBangladeshNumbers() {
        assertEquals(
            listOf("01618219098", "8801618219098"),
            SipClientEngine.buildDialFallbacks("+8801618219098")
        )
        assertEquals(
            listOf("01618219098"),
            SipClientEngine.buildDialFallbacks("8801618219098")
        )
        assertEquals(
            listOf("14155551234"),
            SipClientEngine.buildDialFallbacks("+14155551234")
        )
        assertEquals(
            emptyList<String>(),
            SipClientEngine.buildDialFallbacks("01712345678")
        )
        assertEquals(
            emptyList<String>(),
            SipClientEngine.buildDialFallbacks("1001@sip.example.com")
        )
    }

    @Test
    fun sipMessageCodec_aggregatesMixedCaseHeadersAndBracketsToInResponse() {
        val rawInvite = "INVITE sip:09639170537@10.0.0.5:5060 SIP/2.0\r\n" +
            "Via: SIP/2.0/UDP 203.0.113.10:5060;branch=z9hG4bK-a\r\n" +
            "v: SIP/2.0/UDP 203.0.113.11:5060;branch=z9hG4bK-b\r\n" +
            "Record-Route: <sip:edge.icctalk.com;lr>\r\n" +
            "record-route: <sip:core.icctalk.com;lr>\r\n" +
            "From: <sip:01618219098@icctalk.com>;tag=from1\r\n" +
            "To: sip:09639170537@icctalk.com\r\n" +
            "Call-ID: mix-case-1@icctalk.com\r\n" +
            "CSeq: 1 INVITE\r\n" +
            "Content-Length: 0\r\n\r\n"

        val parsed = SipMessageCodec.parse(rawInvite)!!
        assertEquals(2, parsed.headerValues("Via").size)
        assertEquals(2, parsed.headerValues("Record-Route").size)

        val response = SipMessageCodec.buildResponse(
            statusCode = 180,
            reasonPhrase = "Ringing",
            request = parsed,
            localIp = "103.45.67.89",
            localPort = 45120,
            username = "09639170537",
            transport = SipTransport.UDP,
            toTag = "tag99"
        )
        assertTrue(response.contains("To: <sip:09639170537@icctalk.com>;tag=tag99"))
    }

    @Test
    fun appColorPalette_providesDefaultPlusFiveNewProfessionalThemes() {
        val palettes = AppColorPalette.entries
        assertEquals(6, palettes.size)
        assertEquals(
            listOf("Sapphire", "Emerald", "Amethyst", "Amber", "Ruby", "Teal"),
            palettes.map { it.label }
        )
    }

    @Test
    fun sipMessageCodec_buildsValidOptionsProbeRequest() {
        val req = SipMessageCodec.buildOptionsRequest(
            domain = "icctalk.com",
            port = 5060,
            username = "09639170537",
            localIp = "192.168.1.25",
            localPort = 51000,
            transport = SipTransport.UDP
        )
        assertTrue(req.startsWith("OPTIONS sip:icctalk.com:5060 SIP/2.0"))
        assertTrue(req.contains("Via: SIP/2.0/UDP 192.168.1.25:51000"))
        assertTrue(req.contains("CSeq: 1 OPTIONS"))
        assertTrue(req.contains("From: <sip:09639170537@icctalk.com>"))
    }

    @Test
    fun dialerSuggestions_returnsMultipleDistinctContactsWhenLatestLogsRepeatSameCaller() {
        val now = 1_700_000_000_000L
        val recents = listOf(
            CallLogItem(1, "01711111111", "Ammu", CallDirection.OUTGOING, CallSourceType.SIM_1, "SIM 1", now, 0, null),
            CallLogItem(2, "01711111111", "Ammu", CallDirection.OUTGOING, CallSourceType.SIM_1, "SIM 1", now - 1000, 12, null),
            CallLogItem(3, "+8801711111111", "Ammu", CallDirection.MISSED, CallSourceType.SIM_1, "SIM 1", now - 2000, 0, null),
            CallLogItem(4, "01711111111", "Ammu", CallDirection.OUTGOING, CallSourceType.SIM_1, "SIM 1", now - 3000, 5, null),
            CallLogItem(5, "01722222222", "Tanvir", CallDirection.INCOMING, CallSourceType.SIM_2, "SIM 2", now - 4000, 30, null),
            CallLogItem(6, "01733333333", "Rafiq", CallDirection.OUTGOING, CallSourceType.SIP, "ICC", now - 5000, 45, null),
            CallLogItem(7, "01744444444", "Nadia", CallDirection.OUTGOING, CallSourceType.SIM_1, "SIM 1", now - 6000, 18, null),
            CallLogItem(8, "01755555555", "Siam", CallDirection.INCOMING, CallSourceType.SIM_1, "SIM 1", now - 7000, 22, null)
        )
        val suggestions = DialerViewModel.buildSuggestions(
            query = "",
            recents = recents,
            contacts = emptyList()
        )
        assertEquals(5, suggestions.size)
        assertEquals(
            listOf("Ammu", "Tanvir", "Rafiq", "Nadia", "Siam"),
            suggestions.map { it.displayName }
        )
    }

    @Test
    fun recentsGrouping_mergesConsecutiveSameContactWithinDateBuckets() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2025, java.util.Calendar.JUNE, 15, 14, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        val oneHourAgo = now - 3_600_000L
        val yesterdayAfternoon = now - 24 * 3_600_000L
        val fourDaysAgo = now - 4 * 24 * 3_600_000L

        val entries = listOf(
            EnrichedCallLogItem(
                log = CallLogItem(10, "01711111111", "Ammu", CallDirection.OUTGOING, CallSourceType.SIM_1, "SIM 1", now, 10, null),
                displayName = "Ammu",
                photoUri = null
            ),
            EnrichedCallLogItem(
                log = CallLogItem(9, "+8801711111111", "Ammu", CallDirection.MISSED, CallSourceType.SIM_1, "SIM 1", oneHourAgo, 0, null),
                displayName = "Ammu",
                photoUri = null
            ),
            EnrichedCallLogItem(
                log = CallLogItem(8, "01722222222", "Tanvir", CallDirection.INCOMING, CallSourceType.SIM_2, "SIM 2", oneHourAgo - 1000L, 40, null),
                displayName = "Tanvir",
                photoUri = null
            ),
            EnrichedCallLogItem(
                log = CallLogItem(7, "01711111111", "Ammu", CallDirection.OUTGOING, CallSourceType.SIM_1, "SIM 1", yesterdayAfternoon, 25, null),
                displayName = "Ammu",
                photoUri = null
            ),
            EnrichedCallLogItem(
                log = CallLogItem(6, "01733333333", "Rafiq", CallDirection.OUTGOING, CallSourceType.SIP, "ICC", fourDaysAgo, 60, null),
                displayName = "Rafiq",
                photoUri = null
            )
        )

        val grouped = RecentsViewModel.groupConsecutiveRecents(entries, nowMs = now)
        assertEquals(4, grouped.size)
        assertEquals(2, grouped[0].callCount)
        assertEquals(RecentsDateBucket.TODAY, grouped[0].dateBucket)
        assertEquals("Ammu", grouped[0].latest.displayName)
        assertEquals(1, grouped[1].callCount)
        assertEquals(RecentsDateBucket.TODAY, grouped[1].dateBucket)
        assertEquals(1, grouped[2].callCount)
        assertEquals(RecentsDateBucket.YESTERDAY, grouped[2].dateBucket)
        assertEquals(1, grouped[3].callCount)
        assertEquals(RecentsDateBucket.OLDER, grouped[3].dateBucket)
    }

    @Test
    fun sipFailureClassification_doesNotRetryOrShowErrorBannerOnNoAnswerBusyDecline() {
        val noAnswer = ActiveCallCoordinator.classifySipFailure(480, "SIP 480 Temporarily Unavailable", beforeEarlyRinging = false)
        assertEquals("No answer", noAnswer.userMessage)
        assertFalse(noAnswer.isRetryableFormatAmbiguity)
        assertFalse(noAnswer.showErrorBanner)

        val timeout = ActiveCallCoordinator.classifySipFailure(408, "SIP 408 Request Timeout", beforeEarlyRinging = true)
        assertEquals("No answer", timeout.userMessage)
        assertFalse(timeout.isRetryableFormatAmbiguity)
        assertFalse(timeout.showErrorBanner)

        val busy = ActiveCallCoordinator.classifySipFailure(486, "SIP 486 Busy Here", beforeEarlyRinging = true)
        assertEquals("User busy", busy.userMessage)
        assertFalse(busy.isRetryableFormatAmbiguity)
        assertFalse(busy.showErrorBanner)

        val declined = ActiveCallCoordinator.classifySipFailure(603, "SIP 603 Decline", beforeEarlyRinging = true)
        assertEquals("Call declined", declined.userMessage)
        assertFalse(declined.isRetryableFormatAmbiguity)
        assertFalse(declined.showErrorBanner)

        val notFoundPreRing = ActiveCallCoordinator.classifySipFailure(404, "SIP 404 Not Found", beforeEarlyRinging = true)
        assertTrue(notFoundPreRing.isRetryableFormatAmbiguity)
        assertTrue(notFoundPreRing.showErrorBanner)
    }

    @Test
    fun incomingNotificationSubtitle_excludesCarrierNameAndFitsConciseFormat() {
        assertEquals(
            "Incoming Via SIM 1, +8801783103515",
            CallNotificationManager.formatIncomingSubtitle(
                lineBadge = "SIM 1 • Grameenphone",
                callerHandle = "+8801783103515",
                displayTitle = "Mom"
            )
        )
        assertEquals(
            "Incoming Via SIM 2",
            CallNotificationManager.formatIncomingSubtitle(
                lineBadge = "SIM 2 • Robi",
                callerHandle = "+8801811223344",
                displayTitle = "+8801811223344"
            )
        )
    }

    @Test
    fun autoRecallDecision_triggersOnlyOnEligibleUnansweredOrRejectedOutgoingCalls() {
        // Default settings: Auto Recall disabled by default with 5s timer, Background SIP enabled by default
        val defaultSettings = AppSettingsState()
        assertFalse(defaultSettings.autoRecallEnabled)
        assertEquals(5, defaultSettings.autoRecallDelaySeconds)
        assertTrue(defaultSettings.receiveIncomingSipCalls)

        // Preset formatting: 5s, 15s, 30s, 1m, 5m
        assertEquals(listOf(5, 15, 30, 60, 300), ActiveCallCoordinator.AUTO_RECALL_PRESET_SECONDS)
        assertEquals(
            listOf("5s", "15s", "30s", "1m", "5m"),
            ActiveCallCoordinator.AUTO_RECALL_PRESET_SECONDS.map { ActiveCallCoordinator.formatAutoRecallPreset(it) }
        )
        assertEquals("5:00", ActiveCallCoordinator.formatAutoRecallRemaining(300))
        assertEquals("1:05", ActiveCallCoordinator.formatAutoRecallRemaining(65))
        assertEquals("30s", ActiveCallCoordinator.formatAutoRecallRemaining(30))

        // Disabled -> never triggers
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = false,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.BUSY,
                sipStatusCode = null,
                attempt = 0
            )
        )

        // SIP busy / declined / no answer -> triggers
        assertTrue(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = null,
                sipStatusCode = 486,
                attempt = 0
            )
        )
        assertTrue(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = null,
                sipStatusCode = 603,
                attempt = 1
            )
        )
        assertTrue(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = null,
                sipStatusCode = 480,
                attempt = 2
            )
        )

        // SIP 404/487/auth errors -> never triggers
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = null,
                sipStatusCode = 404,
                attempt = 0
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = null,
                sipStatusCode = 487,
                attempt = 0
            )
        )

        // SIM busy / rejected / missed -> triggers
        assertTrue(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.BUSY,
                sipStatusCode = null,
                attempt = 0
            )
        )
        assertTrue(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.REJECTED,
                sipStatusCode = null,
                attempt = 0
            )
        )

        // Local user hangup, connected call, incoming call, or attempt cap -> never triggers
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = true,
                disconnectCode = DisconnectCause.LOCAL,
                sipStatusCode = null,
                attempt = 0
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = true,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.OTHER,
                sipStatusCode = null,
                attempt = 0
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.INCOMING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.MISSED,
                sipStatusCode = null,
                attempt = 0
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldTriggerAutoRecall(
                enabled = true,
                direction = CallDirection.OUTGOING,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.BUSY,
                sipStatusCode = null,
                attempt = ActiveCallCoordinator.MAX_AUTO_RECALL_ATTEMPTS
            )
        )
    }

    @Test
    fun incomingCallUiMode_usesHeadsUpMiniPopupWhenUnlockedAndInteractive() {
        // Unlocked & screen on with notifications allowed -> show compact heads-up mini popup, not full-screen
        assertFalse(
            CallNotificationManager.shouldLaunchFullScreenIncomingUi(
                isScreenInteractive = true,
                isKeyguardLocked = false,
                hasPostNotificationsPermission = true,
                notificationsEnabled = true,
                channelBlocked = false
            )
        )

        // Screen off or keyguard locked -> open full-screen IncomingCallActivity
        assertTrue(
            CallNotificationManager.shouldLaunchFullScreenIncomingUi(
                isScreenInteractive = false,
                isKeyguardLocked = false,
                hasPostNotificationsPermission = true,
                notificationsEnabled = true,
                channelBlocked = false
            )
        )
        assertTrue(
            CallNotificationManager.shouldLaunchFullScreenIncomingUi(
                isScreenInteractive = true,
                isKeyguardLocked = true,
                hasPostNotificationsPermission = true,
                notificationsEnabled = true,
                channelBlocked = false
            )
        )

        // Notifications denied/blocked -> fallback to full-screen IncomingCallActivity
        assertTrue(
            CallNotificationManager.shouldLaunchFullScreenIncomingUi(
                isScreenInteractive = true,
                isKeyguardLocked = false,
                hasPostNotificationsPermission = false,
                notificationsEnabled = true,
                channelBlocked = false
            )
        )
        assertTrue(
            CallNotificationManager.shouldLaunchFullScreenIncomingUi(
                isScreenInteractive = true,
                isKeyguardLocked = false,
                hasPostNotificationsPermission = true,
                notificationsEnabled = true,
                channelBlocked = true
            )
        )
    }

    @Test
    fun missedCallNotification_formatsSingleAndMultipleCallersAndDeduplicatesSimTelecom() {
        assertEquals("Missed call", CallNotificationManager.formatMissedCallTitle(1))
        assertEquals("3 missed calls", CallNotificationManager.formatMissedCallTitle(3))

        val singleSim = listOf(
            MissedCallRecord(
                callerName = "Mom",
                callerHandle = "+8801783103515",
                lineBadge = "SIM 1 • Grameenphone",
                callerPhotoUri = null,
                timestampMs = 1_000L,
                isSip = false
            )
        )
        assertEquals(
            "Mom (+8801783103515) • SIM 1",
            CallNotificationManager.formatMissedCallText(singleSim, totalCount = 1)
        )

        val multiMixed = listOf(
            MissedCallRecord("Mom", "+8801783103515", "SIM 1 • GP", null, 2_000L, false),
            MissedCallRecord("Office SIP", "01711111111", "ICC • UDP", null, 1_500L, true),
            MissedCallRecord("Tanvir", "01722222222", "SIM 2", null, 1_000L, false)
        )
        assertEquals(
            "Mom, Office SIP (+1 more)",
            CallNotificationManager.formatMissedCallText(multiMixed, totalCount = 3)
        )

        // Same caller within 5s window (e.g. InCallService + Telecom broadcast) deduplicates cleanly
        assertTrue(
            CallNotificationManager.shouldDeduplicateMissedCall(
                lastHandle = "+8801783103515",
                lastTimeMs = 10_000L,
                newHandle = "01783103515",
                newTimeMs = 10_600L
            )
        )
        assertFalse(
            CallNotificationManager.shouldDeduplicateMissedCall(
                lastHandle = "+8801783103515",
                lastTimeMs = 10_000L,
                newHandle = "01783103515",
                newTimeMs = 20_000L
            )
        )

        // Decision rules: unanswered incoming SIM/SIP notifies; user decline / connected / outgoing does not
        assertTrue(
            ActiveCallCoordinator.shouldNotifyMissedCall(
                isIncoming = true,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.MISSED
            )
        )
        assertTrue(
            ActiveCallCoordinator.shouldNotifyMissedCall(
                isIncoming = true,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = null
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldNotifyMissedCall(
                isIncoming = true,
                wasConnected = false,
                endedByLocalUser = true,
                disconnectCode = DisconnectCause.LOCAL
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldNotifyMissedCall(
                isIncoming = true,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.REJECTED
            )
        )
        assertFalse(
            ActiveCallCoordinator.shouldNotifyMissedCall(
                isIncoming = false,
                wasConnected = false,
                endedByLocalUser = false,
                disconnectCode = DisconnectCause.MISSED
            )
        )
    }

    @Test
    fun incomingRingerController_respectsSystemAudioProfiles() {
        assertEquals(
            RingerPolicy(playRingtone = true, vibrate = true),
            IncomingRingerController.resolveRingerPolicy(
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                vibrateWhenRinging = true
            )
        )
        assertEquals(
            RingerPolicy(playRingtone = true, vibrate = false),
            IncomingRingerController.resolveRingerPolicy(
                ringerMode = AudioManager.RINGER_MODE_NORMAL,
                vibrateWhenRinging = false
            )
        )
        assertEquals(
            RingerPolicy(playRingtone = false, vibrate = true),
            IncomingRingerController.resolveRingerPolicy(
                ringerMode = AudioManager.RINGER_MODE_VIBRATE,
                vibrateWhenRinging = true
            )
        )
        assertEquals(
            RingerPolicy(playRingtone = false, vibrate = false),
            IncomingRingerController.resolveRingerPolicy(
                ringerMode = AudioManager.RINGER_MODE_SILENT,
                vibrateWhenRinging = true
            )
        )
    }

    @Test
    fun rtpJitterBuffer_reordersOutOfOrderHandlesWraparoundAndDropsDuplicates() {
        val jb = RtpJitterBuffer(minBufferedFrames = 2, maxBufferSize = 12)
        val f65534 = shortArrayOf(1)
        val f65535 = shortArrayOf(2)
        val f0 = shortArrayOf(3)
        val f1 = shortArrayOf(4)

        jb.offer(65535, f65535)
        // Not yet primed with minBufferedFrames = 2
        assertTrue(jb.pollReady().isEmpty())

        jb.offer(0, f0)
        jb.offer(65534, f65534)
        val ordered = jb.pollReady()
        assertEquals(listOf<Short>(1, 2, 3), ordered.map { it[0] })

        // Duplicate / late packet behind playout pointer (expected = 1) must be ignored
        jb.offer(0, shortArrayOf(99))
        assertTrue(jb.pollReady().isEmpty())

        // Subsequent in-sequence packet plays normally
        jb.offer(1, f1)
        val next = jb.pollReady()
        assertEquals(listOf<Short>(4), next.map { it[0] })
    }

    @Test
    fun sipStandbyStatusAndDoodleThemeDefault_matchExpectedBehavior() {
        assertEquals("Outbound Ready", SipRegistrationStatus.STANDBY.label)
        assertFalse(AppSettingsState().doodleThemeEnabled)
    }

    @Test
    fun sipBalanceFetcher_enforcesHttpsAndSafeSameOriginRedirects() {
        assertNotNull(SipBalanceFetcher.validateBalanceUrl("https://icctalk.com:2020/OTTSSO/login"))
        assertNull(SipBalanceFetcher.validateBalanceUrl("http://icctalk.com/OTTSSO/login"))
        assertNull(SipBalanceFetcher.validateBalanceUrl("https:///path"))

        assertTrue(
            SipBalanceFetcher.isSafeRedirectTarget(
                "https://icctalk.com:2020/OTTSSO/login",
                "https://icctalk.com:2020/OTTSSO/dashboard"
            )
        )
        assertTrue(
            SipBalanceFetcher.isSafeRedirectTarget(
                "https://icctalk.com:2020/OTTSSO/login",
                "https://sso.icctalk.com/session"
            )
        )
        assertFalse(
            SipBalanceFetcher.isSafeRedirectTarget(
                "https://icctalk.com:2020/OTTSSO/login",
                "http://icctalk.com/downgrade"
            )
        )
        assertFalse(
            SipBalanceFetcher.isSafeRedirectTarget(
                "https://icctalk.com:2020/OTTSSO/login",
                "https://evil-icctalk.com/steal"
            )
        )
    }
}
