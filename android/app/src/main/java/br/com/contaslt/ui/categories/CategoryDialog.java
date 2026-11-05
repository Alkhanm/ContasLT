package br.com.contaslt.ui.categories;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import br.com.contaslt.databinding.DialogCategoryBinding;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.CategoryInput;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.views.ColorPaletteView;
import br.com.contaslt.ui.views.SelectFieldView;
import br.com.contaslt.ui.views.UiKit;

/** CategoryDialog.vue: nome, tipo, categoria pai (um nível), cor e ícone. */
public class CategoryDialog extends DialogFragment {

    private static final String ARG_ID = "categoryId";
    private static final String ARG_TYPE = "defaultType";
    private static final String ARG_PARENT = "defaultParentId";
    private static final String NONE = "none";

    private DialogCategoryBinding b;
    private Category category;
    private boolean saving;

    /** {@code categoryId} null = nova; {@code defaultParentId} pré-seleciona a pai ao criar uma subcategoria. */
    public static void show(FragmentManager fm, String categoryId, String defaultType, String defaultParentId) {
        CategoryDialog d = new CategoryDialog();
        Bundle args = new Bundle();
        args.putString(ARG_ID, categoryId);
        args.putString(ARG_TYPE, defaultType);
        args.putString(ARG_PARENT, defaultParentId);
        d.setArguments(args);
        d.show(fm, "category-dialog");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle state) {
        AppCompatDialog d = new AppCompatDialog(requireContext());
        d.supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null) {
            UiKit.cardDialogWindow(getDialog());
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = DialogCategoryBinding.inflate(inflater, container, false);
        Bundle args = requireArguments();
        Catalog cat = Catalog.get();
        category = cat.category(args.getString(ARG_ID));
        Category defaultParent = cat.category(args.getString(ARG_PARENT));
        String defaultType = args.getString(ARG_TYPE);

        b.title.setText(category != null ? "Editar categoria" : "Nova categoria");
        b.name.setText(category != null ? category.name : "");
        String parentId = category != null ? category.parentId : defaultParent != null ? defaultParent.id : null;
        String type = category != null ? category.type : defaultParent != null ? defaultParent.type : defaultType != null ? defaultType : "EXPENSE";
        b.type.setOptions(new String[] {"Despesa", "Receita"}, new String[] {"EXPENSE", "INCOME"});
        UiKit.bindTypeToggle(b.type, type);
        b.type.setOnChange(t -> {
            UiKit.bindTypeToggle(b.type, t);
            renderParents();
        });
        b.parent.setSheetTitle("Subcategoria de");
        b.parent.setOnChange(v -> {
            Category p = Catalog.get().category(v);
            if (p != null) {
                UiKit.bindTypeToggle(b.type, p.type);
            }
            renderTypeLock();
        });
        b.color.setValue(category != null && category.color != null ? category.color
            : ColorPaletteView.PALETTE[new Random().nextInt(ColorPaletteView.PALETTE.length)]);
        b.icon.setValue(category != null && category.icon != null ? category.icon : "tag");
        b.color.setOnChange(c -> renderPreview());
        b.icon.setOnChange(i -> renderPreview());

        boolean hasChildren = category != null && !cat.childrenOf(category.id, true).isEmpty();
        Ui.visible(b.parentBox, !hasChildren);
        Ui.visible(b.hasChildren, hasChildren);
        renderParents();
        b.parent.setValue(parentId == null ? NONE : parentId);
        renderTypeLock();
        renderPreview();
        b.preview.setSize("lg");
        b.close.setOnClickListener(v -> dismiss());
        b.save.setOnClickListener(v -> save());
        return b.getRoot();
    }

    private String parentId() {
        String v = b.parent.value();
        return v == null || NONE.equals(v) ? null : v;
    }

    private void renderParents() {
        String current = b.parent.value();
        List<SelectFieldView.Option> opts = new ArrayList<>();
        opts.add(SelectFieldView.Option.muted(NONE, "Nenhuma (categoria principal)"));
        for (Category c : Catalog.get().roots(b.type.value())) {
            if (category == null || !c.id.equals(category.id)) {
                opts.add(SelectFieldView.Option.icon(c.id, c.name, c.icon, c.color));
            }
        }
        b.parent.setOptions(opts);
        b.parent.setValue(current == null ? NONE : current);
    }

    private void renderTypeLock() {
        boolean locked = category != null || parentId() != null;
        UiKit.setEnabledDeep(b.type, !locked);
        b.type.setAlpha(locked ? 0.6f : 1f);
    }

    private void renderPreview() {
        b.preview.bind(b.icon.value(), b.color.value());
    }

    private void save() {
        String name = b.name.getText().toString().trim();
        if (name.isEmpty()) {
            b.nameError.setText("Informe o nome");
            Ui.visible(b.nameError, true);
            return;
        }
        Ui.visible(b.nameError, false);
        if (saving) {
            return;
        }
        saving = true;
        b.save.setText("Salvando…");
        b.save.setEnabled(false);
        CategoryInput in = new CategoryInput();
        in.name = name;
        in.type = b.type.value();
        in.color = b.color.value();
        in.icon = b.icon.value();
        in.parentId = parentId();
        Category editing = category;
        if (editing != null) {
            in.archived = editing.archived;
        }
        Repo.run(this, () -> editing != null ? CatalogService.updateCategory(editing.id, in) : CatalogService.createCategory(in), ok -> {
            UiKit.toast(getActivity(), editing != null ? "Categoria atualizada" : "Categoria criada");
            dismiss();
        }, err -> {
            saving = false;
            if (b == null) {
                return;
            }
            b.save.setText("Salvar");
            b.save.setEnabled(true);
            String nameErr = err.errors.get("name");
            b.nameError.setText(nameErr);
            Ui.visible(b.nameError, nameErr != null);
            UiKit.snack(b.getRoot(), err.getMessage());
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
