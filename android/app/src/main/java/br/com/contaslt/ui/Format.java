package br.com.contaslt.ui;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** Formatação das telas (porte de frontend/src/lib/format.ts). Valores em centavos. */
public final class Format {

    public static final Locale PT_BR = new Locale("pt", "BR");
    private static final DateTimeFormatter NUMERIC = DateTimeFormatter.ofPattern("dd/MM/yyyy", PT_BR);

    private Format() {
    }

    /** "R$ 1.234,56" (negativo com o sinal de menos tipográfico, como o Intl do PWA). */
    public static String money(long cents) {
        NumberFormat f = NumberFormat.getCurrencyInstance(PT_BR);
        String s = f.format(Math.abs(cents) / 100.0).replace(' ', ' ');
        return cents < 0 ? "−" + s : s;
    }

    public static String money(Long cents) {
        return money(cents == null ? 0 : cents);
    }

    /** "1.234,56". */
    public static String moneyPlain(long cents) {
        NumberFormat f = NumberFormat.getNumberInstance(PT_BR);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return f.format(cents / 100.0);
    }

    /** Despesas negativas, receitas positivas. */
    public static String signedMoney(long cents, String type) {
        return ("EXPENSE".equals(type) ? "−" : "+") + money(Math.abs(cents));
    }

    public static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "Novembro de 2026". */
    public static String monthLabel(String month) {
        YearMonth ym = YearMonth.parse(month);
        return capitalize(ym.getMonth().getDisplayName(TextStyle.FULL, PT_BR)) + " de " + ym.getYear();
    }

    /** "Nov 2026" (cabeçalho). */
    public static String monthNav(String month) {
        YearMonth ym = YearMonth.parse(month);
        return capitalize(ym.getMonth().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", "")) + " " + ym.getYear();
    }

    /** "Nov". */
    public static String monthShortLabel(String month) {
        return capitalize(YearMonth.parse(month).getMonth().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", ""));
    }

    /** "08 nov". */
    public static String dateShort(String iso) {
        LocalDate d = LocalDate.parse(iso);
        return String.format(PT_BR, "%02d %s", d.getDayOfMonth(), d.getMonth().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", ""));
    }

    /** "Dom, 15 de novembro". */
    public static String dateLong(String iso) {
        LocalDate d = LocalDate.parse(iso);
        String wd = d.getDayOfWeek().getDisplayName(TextStyle.SHORT, PT_BR).replace(".", "");
        return capitalize(wd) + ", " + d.getDayOfMonth() + " de " + d.getMonth().getDisplayName(TextStyle.FULL, PT_BR);
    }

    /** "05/11/2026". */
    public static String dateNumeric(String iso) {
        return LocalDate.parse(iso).format(NUMERIC);
    }

    public static String todayIso() {
        return LocalDate.now().toString();
    }

    public static String currentMonth() {
        return YearMonth.now().toString();
    }

    public static String shiftMonth(String month, int delta) {
        return YearMonth.parse(month).plusMonths(delta).toString();
    }

    public enum DueStatus { PAID, OVERDUE, TODAY, SOON, SCHEDULED }

    public static DueStatus dueStatus(boolean paid, String dueDate) {
        if (paid) {
            return DueStatus.PAID;
        }
        LocalDate today = LocalDate.now();
        LocalDate due = LocalDate.parse(dueDate);
        if (due.isBefore(today)) {
            return DueStatus.OVERDUE;
        }
        if (due.isEqual(today)) {
            return DueStatus.TODAY;
        }
        return ChronoUnit.DAYS.between(today, due) <= 7 ? DueStatus.SOON : DueStatus.SCHEDULED;
    }

    public static String dueLabel(boolean paid, String dueDate, String type) {
        boolean income = "INCOME".equals(type);
        return switch (dueStatus(paid, dueDate)) {
            case PAID -> income ? "Recebido" : "Pago";
            case OVERDUE -> income ? "Atrasado" : "Vencido";
            case TODAY -> "Vence hoje";
            case SOON -> "Vence em breve";
            case SCHEDULED -> "Agendado";
        };
    }

    public static String frequencyLabel(String f) {
        return switch (f) {
            case "WEEKLY" -> "Semanal";
            case "BIWEEKLY" -> "Quinzenal";
            case "MONTHLY" -> "Mensal";
            case "BIMONTHLY" -> "Bimestral";
            case "QUARTERLY" -> "Trimestral";
            case "SEMIANNUAL" -> "Semestral";
            case "YEARLY" -> "Anual";
            default -> f;
        };
    }

    public static String accountTypeLabel(String t) {
        return switch (t) {
            case "CHECKING" -> "Conta corrente";
            case "SAVINGS" -> "Poupança";
            case "CREDIT_CARD" -> "Cartão de crédito";
            case "CASH" -> "Dinheiro";
            case "INVESTMENT" -> "Investimento";
            default -> "Outra";
        };
    }

    public static String invoiceStatusLabel(String s) {
        return switch (s) {
            case "OPEN" -> "Aberta";
            case "CLOSED" -> "Fechada";
            case "PARTIAL" -> "Paga em parte";
            case "PAID" -> "Paga";
            default -> s;
        };
    }

    /** Texto digitado ("1.234,56", só dígitos contam) em centavos; null se vazio. */
    public static Long parseMoneyInput(String text) {
        String digits = text.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        return Long.parseLong(digits.length() > 15 ? digits.substring(0, 15) : digits);
    }
}
