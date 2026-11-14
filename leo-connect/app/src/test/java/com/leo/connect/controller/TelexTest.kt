package com.leo.connect.controller

import org.junit.Assert.assertEquals
import org.junit.Test

class TelexTest {
    private fun check(keys: String, expected: String) = assertEquals("gõ \"$keys\"", expected, Telex.type(keys))

    @Test fun dauThanh() {
        check("as", "á"); check("af", "à"); check("ar", "ả"); check("ax", "ã"); check("aj", "ạ")
        check("asz", "a")
        check("ass", "as")
    }

    @Test fun nguyenAmDacBiet() {
        check("aa", "â"); check("ee", "ê"); check("oo", "ô")
        check("aw", "ă"); check("ow", "ơ"); check("uw", "ư"); check("w", "ư")
        check("dd", "đ"); check("ddi", "đi")
        check("aaa", "aa")
    }

    @Test fun tuThuongGap() {
        check("tieesng", "tiếng")
        check("tieengs", "tiếng")
        check("vieetj", "việt")
        check("nguoiwf", "người")
        check("nguwowif", "người")
        check("hoaf", "hòa")
        check("hoangf", "hoàng")
        check("thuyr", "thủy")
        check("quas", "quá")
        check("giaf", "già")
        check("gif", "gì")
        check("dduowcj", "được")
        check("khoong", "không")
        check("cuar", "của")
        check("muwaf", "mừa")
    }

    @Test fun cauHoanChinh() {
        check("Xin chaof, tooi laf Leo.", "Xin chào, tôi là Leo.")
        check("Ddaay laf tieesng Vieetj", "Đây là tiếng Việt")
    }

    @Test fun chuHoa() {
        check("VIEETJ", "VIỆT")
        check("Hoaf", "Hòa")
    }
}
