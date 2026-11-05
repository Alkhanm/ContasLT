package br.com.contaslt.ui.categories;

import java.util.ArrayList;
import java.util.List;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import br.com.contaslt.R;
import br.com.contaslt.databinding.FragmentCategoriesBinding;
import br.com.contaslt.databinding.ItemCatChildBinding;
import br.com.contaslt.databinding.ItemCatRootBinding;
import br.com.contaslt.dto.Category;
import br.com.contaslt.dto.CategoryInput;
import br.com.contaslt.services.CatalogService;
import br.com.contaslt.ui.Catalog;
import br.com.contaslt.ui.Repo;
import br.com.contaslt.ui.Ui;
import br.com.contaslt.ui.Views;
import br.com.contaslt.ui.views.UiKit;

/** Categorias (CategoriesPage.vue): abas Despesas/Receitas, cartões com subcategorias, arquivar e excluir. */
public class CategoriesFragment extends Fragment {

    private FragmentCategoriesBinding b;
    private String tab = "EXPENSE";
    private boolean showArchived;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        b = FragmentCategoriesBinding.inflate(inflater, container, false);
        b.tabs.setOptions(new String[] {"Despesas", "Receitas"}, new String[] {"EXPENSE", "INCOME"});
        b.tabs.setValue(tab);
        b.tabs.setOnChange(t -> {
            tab = t;
            render();
        });
        b.newCategory.setOnClickListener(v -> CategoryDialog.show(getParentFragmentManager(), null, tab, null));
        b.toggleArchived.setOnClickListener(v -> {
            showArchived = !showArchived;
            render();
        });
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        Catalog.live.observe(getViewLifecycleOwner(), c -> render());
    }

    private boolean visible(Category c) {
        return tab.equals(c.type) && (showArchived || !c.archived);
    }

    private void render() {
        if (b == null) {
            return;
        }
        Catalog cat = Catalog.get();
        List<Category> roots = new ArrayList<>();
        int archivedCount = 0;
        for (Category c : cat.categories) {
            if (c.parentId == null && visible(c)) {
                roots.add(c);
            }
            if (tab.equals(c.type) && c.archived) {
                archivedCount++;
            }
        }
        roots.sort((x, y) -> CatalogService.COLLATOR.compare(x.name, y.name));
        b.list.removeAllViews();
        LayoutInflater inf = getLayoutInflater();
        for (Category root : roots) {
            ItemCatRootBinding r = ItemCatRootBinding.inflate(inf, b.list, false);
            r.getRoot().setBackground(UiKit.cardXl(requireContext()));
            r.icon.bind(root.icon, root.color);
            r.name.setText(root.name);
            Ui.visible(r.archived, root.archived);
            if (root.archived) {
                r.head.setAlpha(0.6f);
            }
            r.open.setOnClickListener(v -> CategoryDialog.show(getParentFragmentManager(), root.id, tab, null));
            r.actions.addView(UiKit.iconButton(requireContext(), R.drawable.ic_corner_down_right, R.color.foreground, "Nova subcategoria",
                v -> CategoryDialog.show(getParentFragmentManager(), null, tab, root.id)));
            addActions(r.actions, root);
            List<Category> children = new ArrayList<>();
            for (Category c : cat.childrenOf(root.id, true)) {
                if (visible(c)) {
                    children.add(c);
                }
            }
            if (!children.isEmpty()) {
                Ui.visible(r.children, true);
                r.children.addView(Views.divider(r.children));
                for (Category c : children) {
                    ItemCatChildBinding cr = ItemCatChildBinding.inflate(inf, r.children, false);
                    cr.icon.setSize("sm");
                    cr.icon.bind(c.icon, c.color);
                    cr.name.setText(c.name);
                    Ui.visible(cr.archived, c.archived);
                    if (c.archived) {
                        cr.getRoot().setAlpha(0.6f);
                    }
                    cr.open.setOnClickListener(v -> CategoryDialog.show(getParentFragmentManager(), c.id, tab, null));
                    addActions(cr.actions, c);
                    r.children.addView(cr.getRoot());
                }
            }
            b.list.addView(r.getRoot());
        }
        Ui.visible(b.toggleArchived, archivedCount > 0);
        b.toggleArchived.setText(showArchived ? "Ocultar arquivadas"
            : "Mostrar " + archivedCount + " " + (archivedCount == 1 ? "arquivada" : "arquivadas"));
    }

    private void addActions(LinearLayout actions, Category c) {
        actions.addView(UiKit.iconButton(requireContext(), c.archived ? R.drawable.ic_archive_restore : R.drawable.ic_archive,
            R.color.foreground, c.archived ? "Restaurar" : "Arquivar", v -> toggleArchive(c)));
        actions.addView(UiKit.iconButton(requireContext(), R.drawable.ic_trash_2, R.color.muted_foreground, "Excluir",
            v -> UiKit.confirm(requireContext(), "Excluir categoria?",
                "Só é possível excluir categorias sem lançamentos e sem subcategorias. Se ela já foi usada, arquive-a.",
                "Excluir", true, () -> remove(c))));
    }

    private void toggleArchive(Category c) {
        CategoryInput in = new CategoryInput();
        in.name = c.name;
        in.type = c.type;
        in.color = c.color;
        in.icon = c.icon;
        in.archived = !c.archived;
        in.parentId = c.parentId;
        Repo.run(getViewLifecycleOwner(), b.getRoot(), () -> CatalogService.updateCategory(c.id, in),
            ok -> UiKit.toast(getActivity(), c.archived ? "Categoria restaurada" : "Categoria arquivada"));
    }

    private void remove(Category c) {
        Repo.runAction(getViewLifecycleOwner(), b.getRoot(), () -> CatalogService.deleteCategory(c.id),
            () -> UiKit.toast(getActivity(), "Categoria excluída"));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
