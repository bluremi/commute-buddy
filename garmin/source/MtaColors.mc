import Toybox.Lang;

//! MTA trunk-line color map and CSV split utility for Garmin Monkey C.
//! Mirrors the 9 color groups in Android's MtaLineColors.kt.
module MtaColors {

    //! Returns the MTA trunk-line background color for a given line ID.
    //! Defaults to gray (0xAAAAAA) for unknown lines.
    (:glance)
    function getLineColor(line as String) as Number {
        if (line.equals("1") || line.equals("2") || line.equals("3")) {
            return 0xD82233;
        } else if (line.equals("4") || line.equals("5") || line.equals("6")) {
            return 0x009952;
        } else if (line.equals("7")) {
            return 0x9A38A1;
        } else if (line.equals("A") || line.equals("C") || line.equals("E")) {
            return 0x0062CF;
        } else if (line.equals("B") || line.equals("D") || line.equals("F") || line.equals("M")) {
            return 0xEB6800;
        } else if (line.equals("G")) {
            return 0x799534;
        } else if (line.equals("J") || line.equals("Z")) {
            return 0x8E5C33;
        } else if (line.equals("L") || line.equals("S")) {
            return 0x7C858C;
        } else if (line.equals("N") || line.equals("Q") || line.equals("R") || line.equals("W")) {
            return 0xF6BC26;
        }
        return 0xAAAAAA;
    }

    //! Yellow-background lines (N, Q, R, W) need black text for contrast.
    (:glance)
    function isLightBackground(line as String) as Boolean {
        return line.equals("N") || line.equals("Q") || line.equals("R") || line.equals("W");
    }

    //! Splits a comma-separated string into an array of trimmed, non-empty tokens.
    (:glance)
    function splitCsv(csv as String) as Array<String> {
        var result = [] as Array<String>;
        var len = csv.length();
        var start = 0;
        for (var i = 0; i <= len; i++) {
            var atEnd = (i == len);
            var isComma = !atEnd && (csv.substring(i, i + 1) as String).equals(",");
            if (atEnd || isComma) {
                var token = csv.substring(start, i) as String;
                // trim leading spaces
                while (token.length() > 0 && (token.substring(0, 1) as String).equals(" ")) {
                    token = token.substring(1, token.length()) as String;
                }
                // trim trailing spaces
                while (token.length() > 0 && (token.substring(token.length() - 1, token.length()) as String).equals(" ")) {
                    token = token.substring(0, token.length() - 1) as String;
                }
                if (token.length() > 0) {
                    result.add(token);
                }
                start = i + 1;
            }
        }
        return result;
    }

}
