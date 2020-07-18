package com.absinthe.libchecker.ui.fragment.applist

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import com.absinthe.libchecker.R
import com.absinthe.libchecker.constant.Constants
import com.absinthe.libchecker.constant.GlobalValues
import com.absinthe.libchecker.constant.librarymap.NativeLibMap
import com.absinthe.libchecker.databinding.FragmentLibNativeBinding
import com.absinthe.libchecker.recyclerview.adapter.LibStringAdapter
import com.absinthe.libchecker.recyclerview.diff.LibStringDiffUtil
import com.absinthe.libchecker.ui.fragment.BaseFragment
import com.absinthe.libchecker.utils.ActivityStackManager
import com.absinthe.libchecker.utils.SPUtils
import com.absinthe.libchecker.utils.UiUtils
import com.absinthe.libchecker.view.EXTRA_PKG_NAME
import com.absinthe.libchecker.view.dialogfragment.LibDetailDialogFragment
import com.absinthe.libchecker.viewmodel.DetailViewModel
import com.blankj.utilcode.util.ToastUtils
import rikka.core.util.ClipboardUtils

const val MODE_SORT_BY_SIZE = 0
const val MODE_SORT_BY_LIB = 1

class NativeAnalysisFragment : BaseFragment<FragmentLibNativeBinding>(R.layout.fragment_lib_native) {

    private val viewModel by activityViewModels<DetailViewModel>()
    private val packageName by lazy { arguments?.getString(EXTRA_PKG_NAME) ?: "" }
    private val adapter = LibStringAdapter(LibStringAdapter.Mode.NATIVE)

    override fun initBinding(view: View): FragmentLibNativeBinding = FragmentLibNativeBinding.bind(view)

    override fun init() {
        binding.apply {
            list.apply {
                adapter = this@NativeAnalysisFragment.adapter
                setPadding(paddingStart, paddingTop, paddingEnd, paddingBottom + UiUtils.getNavBarHeight())
            }
            ibSort.setOnClickListener {
                GlobalValues.libSortMode.value =
                    if (GlobalValues.libSortMode.value == MODE_SORT_BY_SIZE) {
                        adapter.setDiffNewData(adapter.data.sortedByDescending {
                            NativeLibMap.contains(it.name)
                        }.toMutableList())
                        MODE_SORT_BY_LIB
                    } else {
                        adapter.setDiffNewData(adapter.data.sortedByDescending { it.size }.toMutableList())
                        MODE_SORT_BY_SIZE
                    }
                SPUtils.putInt(
                    Constants.PREF_LIB_SORT_MODE,
                    GlobalValues.libSortMode.value ?: MODE_SORT_BY_SIZE
                )
            }
        }

        viewModel.apply {
            libItems.observe(viewLifecycleOwner, Observer {
                adapter.setDiffNewData(it.toMutableList())
            })
        }

        fun openLibDetailDialog(position: Int) {
            if (GlobalValues.config.enableLibDetail) {
                val name = adapter.getItem(position).name
                val regexName = NativeLibMap.findRegex(name)?.regexName
                LibDetailDialogFragment.newInstance(name, adapter.mode, regexName)
                    .apply {
                        ActivityStackManager.topActivity?.apply {
                            show(supportFragmentManager, tag)
                        }
                    }
            }
        }

        adapter.apply {
            setOnItemClickListener { _, _, position ->
                openLibDetailDialog(position)
            }
            setOnItemLongClickListener { _, _, position ->
                ClipboardUtils.put(requireContext(), getItem(position).name)
                ToastUtils.showShort(R.string.toast_copied_to_clipboard)
                true
            }
            setOnItemChildClickListener { _, _, position ->
                openLibDetailDialog(position)
            }
            setDiffCallback(LibStringDiffUtil())
        }
        viewModel.initSoAnalysisData(requireContext(), packageName)
    }

    companion object {
        fun newInstance(packageName: String): NativeAnalysisFragment {
            return NativeAnalysisFragment()
                .apply {
                    arguments = Bundle().apply {
                        putString(EXTRA_PKG_NAME, packageName)
                    }
                }
        }
    }
}