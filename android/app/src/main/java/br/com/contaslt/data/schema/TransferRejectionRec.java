package br.com.contaslt.data.schema;

/** "Não é transferência": chaves bill:&lt;id&gt; ou tx:&lt;id&gt;, com aKey &lt; bKey. */
public class TransferRejectionRec extends Rec {
    public String aKey;
    public String bKey;
}
