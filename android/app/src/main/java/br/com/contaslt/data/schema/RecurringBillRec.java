package br.com.contaslt.data.schema;

public class RecurringBillRec extends Rec {
    public String accountId;
    public String categoryId;
    public String description;
    public long amount;
    public String type;
    /** WEEKLY, BIWEEKLY, MONTHLY, BIMONTHLY, QUARTERLY, SEMIANNUAL, YEARLY. */
    public String frequency;
    public String startDate;
    public String endDate;
    public Integer occurrences;
    /** Número da ocorrência em startDate (1 = primeira). */
    public int startNumber = 1;
    /** 1 a 10. */
    public int matchStrictness = 5;
    public String notes;
    public boolean active = true;
}
