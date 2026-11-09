package br.com.contaslt.ui.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import br.com.contaslt.ContasApp;
import br.com.contaslt.R;
import br.com.contaslt.databinding.ItemTxTransferSuggestionBinding;
import br.com.contaslt.databinding.SheetTransferMatchBinding;
import br.com.contaslt.dto.TransferSuggestion;
import br.com.contaslt.dto.TransferSuggestionList;
import br.com.contaslt.dto.User;
import br.com.contaslt.services.TransfersService;
import br.com.contaslt.ui.Format;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.views.MoneyText;

/**
 * Cruzar transferências (TransferMatchSheet.vue): saídas e entradas de mesmo valor em contas diferentes; vincular um
 * par (ou todos), recusar ("Não é transferência") e voltar a sugerir os recusados.
 */
public class TransferMatchSheet extends BottomSheetDialogFragment {

    public static void show(FragmentManager fm) {
        if (fm.findFragmentByTag("transfer-match") == null) {
            new TransferMatchSheet().show(fm, "transfer-match");
        }
    }

    private SheetTransferMatchBinding b;
    private List<TransferSuggestion> items = new ArrayList<>();
    private int rejected;
    private boolean loading = true;
    private boolean linkingAll;
    private final Set<String> busy = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        // Inflado com o contexto da Activity: os seletores de data/lista abrem pelo FragmentManager dela.
        b = SheetTransferMatchBinding.inflate(LayoutInflater.from(requireActivity()), container, false);
        b.close.setOnClickListener(v -> dismiss());
        b.linkAll.setOnClickListener(v -> linkAll());
        return b.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        SheetUtil.expand(getDialog(), false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        load();
    }

    private String userName() {
        User u = ((ContasApp) requireActivity().getApplication()).container().prefs.user();
        return u == null ? null : u.name;
    }

    private void load() {
        String name = userName();
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> TransfersService.transferSuggestions(name), (TransferSuggestionList d) -> {
            loading = false;
            items = new ArrayList<>(d.items);
            rejected = d.rejected;
            render();
        });
        render();
    }

    private static String key(TransferSuggestion s) {
        return s.from.id;
    }

    private void render() {
        if (b == null) {
            return;
        }
        Context c = requireContext();
        b.items.removeAllViews();
        if (loading) {
            for (int i = 0; i < 3; i++) {
                View sk = new View(c);
                GradientDrawable g = new GradientDrawable();
                g.setColor(Ui.color(c, R.color.muted));
                g.setCornerRadius(Ui.dp(c, 12));
                sk.setBackground(g);
                ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 112));
                lp.bottomMargin = Ui.dp(c, 12);
                b.items.addView(sk, new android.widget.LinearLayout.LayoutParams(lp));
            }
        } else if (items.isEmpty()) {
            android.widget.FrameLayout box = new android.widget.FrameLayout(c);
            box.setBackgroundResource(R.drawable.bg_card_dashed);
            box.addView(Views.empty(box, R.drawable.ic_arrow_left_right, "Nenhum par encontrado",
                "Quando uma saída e uma entrada de mesmo valor aparecerem em contas diferentes, elas serão sugeridas aqui. Você também pode criar uma transferência pelo botão +.",
                null, null));
            b.items.addView(box);
        } else {
            LayoutInflater inf = LayoutInflater.from(c);
            for (TransferSuggestion s : items) {
                b.items.addView(row(inf, c, s));
            }
        }

        Ui.visible(b.rejected, !loading && rejected > 0);
        if (rejected > 0) {
            SpannableStringBuilder t = new SpannableStringBuilder(rejected + " "
                + (rejected == 1 ? "par recusado não aparece" : "pares recusados não aparecem") + " aqui. ");
            int start = t.length();
            t.append("Voltar a sugerir");
            t.setSpan(new ForegroundColorSpan(Ui.color(c, R.color.primary)), start, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, t.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.rejected.setText(t);
            b.rejected.setOnClickListener(v -> forgetRejections());
        }

        Ui.visible(b.footer, !items.isEmpty());
        if (!items.isEmpty()) {
            long total = 0;
            for (TransferSuggestion s : items) {
                total += s.from.amount;
            }
            SpannableStringBuilder label = new SpannableStringBuilder(linkingAll ? "Vinculando…"
                : "Vincular " + (items.size() == 1 ? "esta" : "todas as " + items.size()));
            if (items.size() > 1) {
                int start = label.length();
                label.append(" · ").append(Format.money(total));
                if (android.os.Build.VERSION.SDK_INT >= 28) { // TypefaceSpan(Typeface) não existe no Android 8
                    label.setSpan(new android.text.style.TypefaceSpan(android.graphics.Typeface.create(
                        androidx.core.content.res.ResourcesCompat.getFont(c, R.font.manrope), android.graphics.Typeface.NORMAL)),
                        start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                label.setSpan(new ForegroundColorSpan((Ui.color(c, R.color.primary_foreground) & 0x00FFFFFF) | 0xCC000000),
                    start, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            b.linkAll.setText(label);
            b.linkAll.setEnabled(!linkingAll);
        }
    }

    private View row(LayoutInflater inf, Context c, TransferSuggestion s) {
        ItemTxTransferSuggestionBinding r = ItemTxTransferSuggestionBinding.inflate(inf, b.items, false);
        chip(r.fromAccount, R.color.expense);
        chip(r.toAccount, R.color.income);
        r.fromAccount.setText(s.from.accountName);
        r.fromDescription.setText(s.from.description);
        r.fromDate.setText(Format.dateShort(s.from.dueDate));
        r.toAccount.setText(s.to.accountName);
        r.toDescription.setText(s.to.description);
        r.toDate.setText(Format.dateShort(s.to.dueDate));
        SpannableStringBuilder a = new SpannableStringBuilder();
        int start = a.length();
        MoneyText.append(a, c, s.from.amount, null, false, false, true);
        a.setSpan(new android.text.style.RelativeSizeSpan(14f / 12f), start, a.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        a.append(s.daysApart > 0 ? " · " + s.daysApart + (s.daysApart == 1 ? " dia de diferença" : " dias de diferença") : " · mesmo dia");
        r.amount.setText(a);
        boolean disabled = busy.contains(key(s)) || linkingAll;
        r.reject.setEnabled(!disabled);
        r.link.setEnabled(!disabled);
        r.reject.setContentDescription("Não é transferência");
        r.reject.setOnClickListener(v -> reject(s));
        r.link.setOnClickListener(v -> link(s));
        return r.getRoot();
    }

    private static void chip(android.widget.TextView tv, int colorRes) {
        Context c = tv.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(c, 4));
        g.setColor((Ui.color(c, colorRes) & 0x00FFFFFF) | 0x1A000000);
        tv.setBackground(g);
    }

    /** "Não é transferência": some daqui e, entre duas contas recorrentes, de todos os meses. */
    private void reject(TransferSuggestion s) {
        busy.add(key(s));
        render();
        View anchor = b.getRoot();
        Repo.run(getViewLifecycleOwner(), () -> TransfersService.rejectTransfer(s.from.id, s.to.id), recurring -> {
            busy.remove(key(s));
            items.removeIf(x -> key(x).equals(key(s)));
            rejected++;
            render();
            SheetUtil.snack(anchor, Boolean.TRUE.equals(recurring)
                ? "Recusado. Essas duas contas recorrentes não serão mais sugeridas como transferência."
                : "Recusado. Esse par não será mais sugerido.");
        }, e -> {
            busy.remove(key(s));
            render();
            SheetUtil.snack(anchor, e.getMessage());
        });
    }

    private void forgetRejections() {
        View anchor = b.getRoot();
        Repo.runAction(getViewLifecycleOwner(), anchor, TransfersService::forgetRejections, () -> {
            rejected = 0;
            SheetUtil.snack(anchor, "Pares recusados voltam a ser sugeridos");
            load();
        });
    }

    private void link(TransferSuggestion s) {
        busy.add(key(s));
        render();
        View anchor = b.getRoot();
        Repo.run(getViewLifecycleOwner(), () -> TransfersService.linkTransfer(s.from.id, s.to.id), t -> {
            busy.remove(key(s));
            items.removeIf(x -> key(x).equals(key(s)));
            render();
            SheetUtil.snack(anchor, "Transferência vinculada");
        }, e -> {
            busy.remove(key(s));
            render();
            SheetUtil.snack(anchor, e.getMessage());
        });
    }

    /** Vincula um a um; o que falhar continua na lista. */
    private void linkAll() {
        linkingAll = true;
        List<TransferSuggestion> batch = new ArrayList<>(items);
        render();
        View anchor = b.getRoot();
        Repo.run(getViewLifecycleOwner(), () -> {
            List<String> linked = new ArrayList<>();
            for (TransferSuggestion s : batch) {
                try {
                    TransfersService.linkTransfer(s.from.id, s.to.id);
                    linked.add(key(s));
                } catch (RuntimeException e) {
                    // segue para o próximo; o que falhou continua na lista
                }
            }
            return linked;
        }, linked -> {
            linkingAll = false;
            items.removeIf(x -> linked.contains(key(x)));
            render();
            int ok = linked.size();
            if (ok > 0) {
                SheetUtil.snack(anchor, ok + " " + (ok == 1 ? "transferência vinculada" : "transferências vinculadas"));
            }
            if (ok < batch.size()) {
                SheetUtil.snack(anchor, (batch.size() - ok) + " não puderam ser vinculadas");
            }
        }, e -> {
            linkingAll = false;
            render();
            SheetUtil.snack(anchor, e.getMessage());
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
