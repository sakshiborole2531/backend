package com.unichem.inventory.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Robust parsing for SAP / Excel / CSV values (SAP India DD.MM.YYYY, trailing minus, Indian digit grouping). */
public final class Parsing {
    private Parsing() {}
    private static final String[] MONTHS = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

    private static final Pattern CURRENCY_WS = Pattern.compile("(?i)[₹\\s]|INR|Rs\\.?");
    private static final Pattern EURO_NUM = Pattern.compile("-?\\d{1,3}(\\.\\d{3})+(,\\d+)?");
    private static final Pattern DEC_COMMA = Pattern.compile("-?\\d+,\\d{1,2}");
    private static final Pattern ZERO_ONLY = Pattern.compile("0+");
    private static final Pattern ZERO_DATE = Pattern.compile("00[./-]00[./-]0000");
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2}).*");
    private static final Pattern DMY_4 = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})$");
    private static final Pattern DMY_2 = Pattern.compile("^(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2})$");
    private static final Pattern YMD_8 = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern DM_MON_Y = Pattern.compile("^(\\d{1,2})[ -]([A-Za-z]{3})[A-Za-z]*[ -,]*(\\d{4})$");
    private static final Pattern EXCEL_SERIAL = Pattern.compile("^\\d{5}(\\.0+)?$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]");

    public static double num(String v) {
        if (v == null || v.isEmpty()) return 0;
        String s = CURRENCY_WS.matcher(v.trim()).replaceAll("");
        if (s.isEmpty()) return 0;
        boolean neg = false;
        if (s.endsWith("-")) { neg = true; s = s.substring(0, s.length() - 1); }
        if (s.startsWith("(") && s.endsWith(")")) { neg = true; s = s.substring(1, s.length() - 1); }
        if (EURO_NUM.matcher(s).matches()) s = s.replace(".", "").replace(",", ".");      // 1.234,56
        else if (DEC_COMMA.matcher(s).matches()) s = s.replace(",", ".");                  // 12,5
        else s = s.replace(",", "");                                                       // 1,23,456.50
        try { double n = Double.parseDouble(s); return neg ? -Math.abs(n) : n; } catch (NumberFormatException e) { return 0; }
    }

    private static LocalDate mk(int y, int m, int d) { try { return LocalDate.of(y, m, d); } catch (DateTimeException e) { return null; } }

    /** Returns null for blank; throws IllegalArgumentException for unrecognised text. */
    public static LocalDate date(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.isEmpty() || ZERO_ONLY.matcher(s).matches() || ZERO_DATE.matcher(s).matches()) return null;
        Matcher m;
        if ((m = ISO_DATE.matcher(s)).matches()) return req(mk(i(m, 1), i(m, 2), i(m, 3)), s);
        if ((m = DMY_4.matcher(s)).matches()) return req(mk(i(m, 3), i(m, 2), i(m, 1)), s);
        if ((m = DMY_2.matcher(s)).matches()) return req(mk(2000 + i(m, 3), i(m, 2), i(m, 1)), s);
        if ((m = YMD_8.matcher(s)).matches()) return req(mk(i(m, 1), i(m, 2), i(m, 3)), s);
        if ((m = DM_MON_Y.matcher(s)).matches()) {
            for (int k = 0; k < 12; k++) if (MONTHS[k].equalsIgnoreCase(m.group(2))) return req(mk(i(m, 3), k + 1, i(m, 1)), s);
        }
        if (EXCEL_SERIAL.matcher(s).matches()) { double serial = Double.parseDouble(s); if (serial > 20000 && serial < 80000) return LocalDate.of(1899, 12, 30).plusDays((long) serial); }
        throw new IllegalArgumentException("Date \"" + s + "\" not recognised");
    }

    private static LocalDate req(LocalDate d, String s) { if (d == null) throw new IllegalArgumentException("Date \"" + s + "\" not recognised"); return d; }
    private static int i(Matcher m, int g) { return Integer.parseInt(m.group(g)); }

    public static String norm(String s) { return s == null ? "" : NON_ALNUM.matcher(s.toLowerCase()).replaceAll(""); }
}
