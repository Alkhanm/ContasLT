package br.com.contaslt.data.schema;

public class ImportBatchRec extends Rec {
    public String accountId;
    /** OFX ou EXCEL. */
    public String source;
    public String filename;
    public int importedCount;
}
