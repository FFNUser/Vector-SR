/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2026 LSPosed Contributors
 */

package org.lsposed.manager.ui.fragment;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.lsposed.manager.App;
import org.lsposed.manager.R;
import org.lsposed.manager.adapters.AppHelper;
import org.lsposed.manager.databinding.FragmentAppListBinding;
import org.lsposed.manager.databinding.ItemModuleBinding;
import org.lsposed.manager.ui.activity.MainActivity;
import org.lsposed.manager.ui.widget.EmptyStateRecyclerView;
import org.lsposed.manager.util.GlideApp;
import org.lsposed.manager.util.ModuleUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import rikka.core.util.ResourceUtils;
import rikka.recyclerview.RecyclerViewKt;

public class NoInlineAppsFragment extends BaseFragment implements MenuProvider {

    private FragmentAppListBinding binding;
    private SearchView searchView;
    private NoInlineAppsAdapter adapter;

    private final RecyclerView.AdapterDataObserver observer = new RecyclerView.AdapterDataObserver() {
        @Override
        public void onChanged() {
            if (binding != null && adapter != null) {
                binding.swipeRefreshLayout.setRefreshing(!adapter.isLoaded());
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentAppListBinding.inflate(inflater, container, false);
        binding.getRoot().setBackgroundColor(ResourceUtils.resolveColor(requireActivity().getTheme(), android.R.attr.colorBackground));
        binding.appBar.setLiftable(true);
        binding.fab.setVisibility(View.GONE);

        adapter = new NoInlineAppsAdapter(this);
        adapter.setHasStableIds(true);
        adapter.registerAdapterDataObserver(observer);

        binding.recyclerView.setAdapter(adapter);
        binding.recyclerView.setHasFixedSize(true);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        binding.recyclerView.getBorderViewDelegate().setBorderVisibilityChangedListener((top, oldTop, bottom, oldBottom) -> binding.appBar.setLifted(!top));
        RecyclerViewKt.fixEdgeEffect(binding.recyclerView, false, true);
        binding.swipeRefreshLayout.setOnRefreshListener(() -> adapter.refresh(true));
        binding.swipeRefreshLayout.setProgressViewEndTarget(true, binding.swipeRefreshLayout.getProgressViewEndOffset());

        setupToolbar(binding.toolbar, binding.clickView, getString(R.string.no_inline_apps), R.menu.menu_no_inline_apps,
                view -> ((MainActivity) requireActivity()).hideNoInlineApps());
        View.OnClickListener listener = v -> {
            if (searchView == null || searchView.isIconified()) {
                binding.recyclerView.smoothScrollToPosition(0);
                binding.appBar.setExpanded(true, true);
            }
        };
        binding.toolbar.setOnClickListener(listener);
        binding.clickView.setOnClickListener(listener);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                ((MainActivity) requireActivity()).hideNoInlineApps();
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) adapter.refresh();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (adapter != null) adapter.unregisterAdapterDataObserver(observer);
        binding = null;
        searchView = null;
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        return adapter != null && adapter.onOptionsItemSelected(item);
    }

    @Override
    public void onPrepareMenu(@NonNull Menu menu) {
        searchView = (SearchView) menu.findItem(R.id.menu_search).getActionView();
        searchView.setIconifiedByDefault(false);
        searchView.setIconified(false);
        searchView.setQueryHint(getString(android.R.string.search_go));
        searchView.setOnQueryTextListener(adapter.getSearchListener());
        searchView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                if (binding == null) return;
                binding.appBar.setExpanded(false, true);
                binding.recyclerView.setNestedScrollingEnabled(false);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (binding == null) return;
                binding.recyclerView.setNestedScrollingEnabled(true);
            }
        });
        searchView.findViewById(androidx.appcompat.R.id.search_edit_frame).setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT);
        adapter.onPrepareOptionsMenu(menu);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
    }

        private static class NoInlineAppsAdapter extends EmptyStateRecyclerView.EmptyStateAdapter<NoInlineAppsAdapter.ViewHolder> implements Filterable {

        private static final String PREF_NO_INLINE_APP_PACKAGES = "no_inline_app_packages";

        private final NoInlineAppsFragment fragment;
        private final PackageManager packageManager;
        private final SharedPreferences preferences;
        private final ModuleUtil moduleUtil;
        private final Drawable defaultIcon;

        private Set<String> checkedPackages = new HashSet<>();
        private List<AppInfo> searchList = new ArrayList<>();
        private List<AppInfo> showList = new ArrayList<>();
        private boolean isLoaded = false;

        private NoInlineAppsAdapter(NoInlineAppsFragment fragment) {
            this.fragment = fragment;
            packageManager = fragment.requireActivity().getPackageManager();
            preferences = App.getPreferences();
            moduleUtil = ModuleUtil.getInstance();
            defaultIcon = packageManager.getDefaultActivityIcon();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(ItemModuleBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.checkbox.setOnCheckedChangeListener(null);
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            var appInfo = showList.get(position);
            holder.appName.setText(appInfo.label);
            holder.appPackageName.setText(appInfo.packageName);
            holder.appPackageName.setVisibility(View.VISIBLE);
            holder.appVersionName.setText(fragment.getString(R.string.app_version, appInfo.packageInfo.versionName));
            holder.appVersionName.setVisibility(View.VISIBLE);
            holder.hint.setVisibility(View.GONE);
            GlideApp.with(holder.appIcon).load(appInfo.packageInfo).into(new CustomTarget<Drawable>() {
                @Override
                public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                    holder.appIcon.setImageDrawable(resource);
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                }

                @Override
                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                    holder.appIcon.setImageDrawable(defaultIcon);
                }
            });

            holder.checkbox.setOnCheckedChangeListener(null);
            holder.checkbox.setChecked(isPackageChecked(appInfo.packageName));
            holder.checkbox.setOnCheckedChangeListener((buttonView, isChecked) -> onCheckedChange(buttonView, isChecked, appInfo));
            holder.itemView.setOnClickListener(v -> holder.checkbox.toggle());
        }

        @Override
        public long getItemId(int position) {
            return showList.get(position).packageName.hashCode();
        }

        @Override
        public int getItemCount() {
            return showList.size();
        }

        @Override
        public boolean isLoaded() {
            return isLoaded;
        }

        private void setLoaded(@Nullable List<AppInfo> list, boolean loaded) {
            fragment.runOnUiThread(() -> {
                if (list != null) showList = list;
                isLoaded = loaded;
                notifyDataSetChanged();
            });
        }

        private boolean shouldHideApp(PackageInfo info, Set<String> checked) {
            if (isPackageChecked(checked, info.packageName)) {
                return false;
            }
            if (preferences.getBoolean("filter_modules", true) &&
                    moduleUtil.getModule(info.packageName, info.applicationInfo.uid / App.PER_USER_RANGE) != null) {
                return true;
            }
            if (preferences.getBoolean("filter_games", true)) {
                if (info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME) {
                    return true;
                }
                //noinspection deprecation
                if ((info.applicationInfo.flags & ApplicationInfo.FLAG_IS_GAME) != 0) {
                    return true;
                }
            }
            return preferences.getBoolean("filter_system_apps", true) &&
                    (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }

        private int sortApps(AppInfo x, AppInfo y) {
            Comparator<PackageInfo> comparator = AppHelper.getAppListComparator(preferences.getInt("list_sort", 0), packageManager);
            boolean xChecked = isPackageChecked(x.packageName);
            boolean yChecked = isPackageChecked(y.packageName);
            if (xChecked == yChecked) {
                return comparator.compare(x.packageInfo, y.packageInfo);
            } else if (xChecked) {
                return -1;
            } else {
                return 1;
            }
        }

        private void refresh() {
            refresh(false);
        }

        private void refresh(boolean force) {
            setLoaded(null, false);
            fragment.runAsync(() -> {
                var selected = loadCheckedPackages();
                var seen = new HashSet<String>();
                var tmpList = new ArrayList<AppInfo>();
                for (var info : AppHelper.getAppList(force)) {
                    if (info == null || info.applicationInfo == null || "system".equals(info.packageName)) {
                        continue;
                    }
                    if (!seen.add(info.packageName)) {
                        continue;
                    }
                    if (shouldHideApp(info, selected)) {
                        continue;
                    }
                    var appInfo = new AppInfo();
                    appInfo.packageInfo = info;
                    appInfo.packageName = info.packageName;
                    appInfo.label = AppHelper.getAppLabel(info, packageManager);
                    tmpList.add(appInfo);
                }
                checkedPackages = selected;
                searchList = tmpList.parallelStream().sorted(this::sortApps).collect(Collectors.toList());
                String query = fragment.searchView != null ? fragment.searchView.getQuery().toString() : "";
                fragment.runOnUiThread(() -> getFilter().filter(query));
            });
        }

        private void onCheckedChange(CompoundButton buttonView, boolean isChecked, AppInfo appInfo) {
            var newCheckedPackages = new HashSet<>(checkedPackages);
            if (isChecked) {
                removePackageEntries(newCheckedPackages, appInfo.packageName);
                newCheckedPackages.add(appInfo.packageName);
            } else {
                removePackageEntries(newCheckedPackages, appInfo.packageName);
            }
            if (saveCheckedPackages(newCheckedPackages)) {
                checkedPackages = newCheckedPackages;
                fragment.showHint(R.string.no_inline_saved, true);
                refresh();
            } else {
                fragment.showHint(R.string.no_inline_save_failed, true);
                buttonView.setChecked(!isChecked);
            }
        }

        private Set<String> loadCheckedPackages() {
            var stored = preferences.getStringSet(PREF_NO_INLINE_APP_PACKAGES, null);
            return stored == null ? new HashSet<>() : new HashSet<>(stored);
        }

        private boolean saveCheckedPackages(Set<String> packages) {
            return preferences.edit()
                    .putStringSet(PREF_NO_INLINE_APP_PACKAGES, new HashSet<>(packages))
                    .commit();
        }

        private boolean isPackageChecked(String packageName) {
            return isPackageChecked(checkedPackages, packageName);
        }

        private boolean isPackageChecked(Set<String> checked, String packageName) {
            if (checked.contains(packageName)) {
                return true;
            }
            String processPrefix = packageName + ":";
            for (String configName : checked) {
                if (configName.startsWith(processPrefix)) {
                    return true;
                }
            }
            return false;
        }

        private void removePackageEntries(Set<String> checked, String packageName) {
            String processPrefix = packageName + ":";
            checked.removeIf(configName -> configName.equals(packageName) || configName.startsWith(processPrefix));
        }

        private boolean onOptionsItemSelected(MenuItem item) {
            int itemId = item.getItemId();
            if (itemId == R.id.item_filter_system) {
                item.setChecked(!item.isChecked());
                preferences.edit().putBoolean("filter_system_apps", item.isChecked()).apply();
            } else if (itemId == R.id.item_filter_games) {
                item.setChecked(!item.isChecked());
                preferences.edit().putBoolean("filter_games", item.isChecked()).apply();
            } else if (itemId == R.id.item_filter_modules) {
                item.setChecked(!item.isChecked());
                preferences.edit().putBoolean("filter_modules", item.isChecked()).apply();
            } else if (!AppHelper.onOptionsItemSelected(item, preferences)) {
                return false;
            }
            refresh();
            return true;
        }

        private void onPrepareOptionsMenu(@NonNull Menu menu) {
            menu.findItem(R.id.item_filter_system).setChecked(preferences.getBoolean("filter_system_apps", true));
            menu.findItem(R.id.item_filter_games).setChecked(preferences.getBoolean("filter_games", true));
            menu.findItem(R.id.item_filter_modules).setChecked(preferences.getBoolean("filter_modules", true));
            switch (preferences.getInt("list_sort", 0)) {
                case 7 -> {
                    menu.findItem(R.id.item_sort_by_update_time).setChecked(true);
                    menu.findItem(R.id.reverse).setChecked(true);
                }
                case 6 -> menu.findItem(R.id.item_sort_by_update_time).setChecked(true);
                case 5 -> {
                    menu.findItem(R.id.item_sort_by_install_time).setChecked(true);
                    menu.findItem(R.id.reverse).setChecked(true);
                }
                case 4 -> menu.findItem(R.id.item_sort_by_install_time).setChecked(true);
                case 3 -> {
                    menu.findItem(R.id.item_sort_by_package_name).setChecked(true);
                    menu.findItem(R.id.reverse).setChecked(true);
                }
                case 2 -> menu.findItem(R.id.item_sort_by_package_name).setChecked(true);
                case 1 -> {
                    menu.findItem(R.id.item_sort_by_name).setChecked(true);
                    menu.findItem(R.id.reverse).setChecked(true);
                }
                case 0 -> menu.findItem(R.id.item_sort_by_name).setChecked(true);
            }
        }

        @NonNull
        @Override
        public Filter getFilter() {
            return new Filter() {
                private boolean lowercaseContains(CharSequence value, String filter) {
                    return !TextUtils.isEmpty(value) && value.toString().toLowerCase().contains(filter);
                }

                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    var filterResults = new FilterResults();
                    var filtered = new ArrayList<AppInfo>();
                    String filter = constraint.toString().toLowerCase();
                    for (var info : searchList) {
                        if (lowercaseContains(info.label, filter) || lowercaseContains(info.packageName, filter)) {
                            filtered.add(info);
                        }
                    }
                    filterResults.values = filtered;
                    filterResults.count = filtered.size();
                    return filterResults;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    //noinspection unchecked
                    setLoaded((List<AppInfo>) results.values, true);
                }
            };
        }

        private SearchView.OnQueryTextListener getSearchListener() {
            return new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(String query) {
                    getFilter().filter(query);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(String query) {
                    getFilter().filter(query);
                    return true;
                }
            };
        }

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView appIcon;
            private final TextView appName;
            private final TextView appPackageName;
            private final TextView appVersionName;
            private final TextView hint;
            private final MaterialCheckBox checkbox;

            private ViewHolder(ItemModuleBinding binding) {
                super(binding.getRoot());
                appIcon = binding.appIcon;
                appName = binding.appName;
                appPackageName = binding.appPackageName;
                appVersionName = binding.appVersionName;
                hint = binding.hint;
                checkbox = binding.checkbox;
                checkbox.setVisibility(View.VISIBLE);
            }
        }

        private static class AppInfo {
            private PackageInfo packageInfo;
            private String packageName;
            private CharSequence label;
        }
    }
}
