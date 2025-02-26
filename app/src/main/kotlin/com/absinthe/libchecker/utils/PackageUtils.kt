package com.absinthe.libchecker.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ApplicationInfoHidden
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInfoHidden
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.collection.arrayMapOf
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.text.isDigitsOnly
import com.absinthe.libchecker.LibCheckerApp
import com.absinthe.libchecker.R
import com.absinthe.libchecker.SystemServices
import com.absinthe.libchecker.annotation.ACTIVITY
import com.absinthe.libchecker.annotation.LibType
import com.absinthe.libchecker.annotation.PROVIDER
import com.absinthe.libchecker.annotation.RECEIVER
import com.absinthe.libchecker.annotation.SERVICE
import com.absinthe.libchecker.bean.KotlinToolingMetadata
import com.absinthe.libchecker.bean.LibStringItem
import com.absinthe.libchecker.bean.StatefulComponent
import com.absinthe.libchecker.constant.Constants
import com.absinthe.libchecker.constant.Constants.ARMV5
import com.absinthe.libchecker.constant.Constants.ARMV5_STRING
import com.absinthe.libchecker.constant.Constants.ARMV7
import com.absinthe.libchecker.constant.Constants.ARMV7_STRING
import com.absinthe.libchecker.constant.Constants.ARMV8
import com.absinthe.libchecker.constant.Constants.ARMV8_STRING
import com.absinthe.libchecker.constant.Constants.ERROR
import com.absinthe.libchecker.constant.Constants.MULTI_ARCH
import com.absinthe.libchecker.constant.Constants.NO_LIBS
import com.absinthe.libchecker.constant.Constants.OVERLAY
import com.absinthe.libchecker.constant.Constants.X86
import com.absinthe.libchecker.constant.Constants.X86_64
import com.absinthe.libchecker.constant.Constants.X86_64_STRING
import com.absinthe.libchecker.constant.Constants.X86_STRING
import com.absinthe.libchecker.constant.librarymap.DexLibMap
import com.absinthe.libchecker.database.AppItemRepository
import com.absinthe.libchecker.utils.dex.FastDexFileFactory
import com.absinthe.libchecker.utils.manifest.ManifestReader
import com.absinthe.libchecker.utils.manifest.StaticLibraryReader
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.delay
import okio.buffer
import okio.source
import org.jf.dexlib2.Opcodes
import timber.log.Timber
import java.io.File
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object PackageUtils {

  /**
   * Get packageInfo
   * @param info ApplicationInfo
   * @param flag Flag mask
   * @return PackageInfo
   * @throws PackageManager.NameNotFoundException
   */
  @Throws(PackageManager.NameNotFoundException::class)
  fun getPackageInfo(info: ApplicationInfo, flag: Int = 0): PackageInfo {
    return getPackageInfo(info.packageName, flag)
  }

  /**
   * Get packageInfo
   * @param packageName Package name string
   * @param flag Flag mask
   * @return PackageInfo
   * @throws PackageManager.NameNotFoundException
   */
  @Throws(PackageManager.NameNotFoundException::class)
  fun getPackageInfo(packageName: String, flag: Int = 0): PackageInfo {
    val packageInfo = SystemServices.packageManager.getPackageInfo(
      packageName,
      VersionCompat.MATCH_UNINSTALLED_PACKAGES or VersionCompat.MATCH_DISABLED_COMPONENTS or flag
    )
    if (FreezeUtils.isAppFrozen(packageInfo.applicationInfo)) {
      return SystemServices.packageManager.getPackageArchiveInfo(
        packageInfo.applicationInfo.sourceDir, VersionCompat.MATCH_DISABLED_COMPONENTS or flag
      )?.apply {
        applicationInfo.sourceDir = packageInfo.applicationInfo.sourceDir
        applicationInfo.nativeLibraryDir = packageInfo.applicationInfo.nativeLibraryDir
      } ?: throw PackageManager.NameNotFoundException()
    }
    return packageInfo
  }

  /**
   * Get all installed apps in device
   * @return list of apps
   * @throws Exception
   */
  @Throws(Exception::class)
  fun getInstallApplications(): List<PackageInfo> {
    return SystemServices.packageManager.getInstalledPackages(
      VersionCompat.MATCH_UNINSTALLED_PACKAGES
        or PackageManager.GET_META_DATA
        or PackageManager.GET_PERMISSIONS
    )
  }

  /**
   * Get version code of an app
   * @param packageInfo PackageInfo
   * @return version code as Long Integer
   */
  fun getVersionCode(packageInfo: PackageInfo): Long {
    return PackageInfoCompat.getLongVersionCode(packageInfo)
  }

  /**
   * Get version code of an app
   * @param packageName packageName
   * @return version code as Long Integer
   */
  fun getVersionCode(packageName: String): Long {
    return PackageInfoCompat.getLongVersionCode(getPackageInfo(packageName))
  }

  /**
   * Get version string of an app ( 1.0.0(1) )
   * @param packageInfo PackageInfo
   * @return version code as String
   */
  fun getVersionString(packageInfo: PackageInfo): String {
    return try {
      "${packageInfo.versionName ?: "<unknown>"} (${getVersionCode(packageInfo)})"
    } catch (e: PackageManager.NameNotFoundException) {
      "Unknown"
    }
  }

  /**
   * Get version string of an app ( 1.0.0(1) )
   * @param versionName Version name
   * @param versionCode Version code
   * @return version code as String
   */
  fun getVersionString(versionName: String, versionCode: Long): String {
    return "$versionName ($versionCode)"
  }

  /**
   * Get target api string of an app ( API 30 )
   * @param packageName PackageName
   * @return version code as String
   */
  fun getTargetApiString(packageName: String): String {
    return getTargetApiString(getPackageInfo(packageName))
  }

  fun getTargetApiString(packageInfo: PackageInfo): String {
    return try {
      "targetSdkVersion ${packageInfo.applicationInfo.targetSdkVersion}"
    } catch (e: PackageManager.NameNotFoundException) {
      "targetSdkVersion ?"
    }
  }

  fun getTargetApiString(targetSdkVersion: Short) = "Target API $targetSdkVersion"

  /**
   * Get native libraries of an app
   * @param packageInfo PackageInfo
   * @param needStaticLibrary True if need get static libraries
   * @param specifiedAbi Specify an ABI
   * @return List of LibStringItem
   */
  fun getNativeDirLibs(
    packageInfo: PackageInfo,
    needStaticLibrary: Boolean = false,
    specifiedAbi: Int? = null
  ): List<LibStringItem> {
    val nativePath = packageInfo.applicationInfo.nativeLibraryDir
    val list = mutableListOf<LibStringItem>()

    if (nativePath != null) {
      File(nativePath).listFiles()?.let { files ->
        list.addAll(
          files.asSequence()
            .distinctBy { it.name }
            .map { LibStringItem(it.name, FileUtils.getFileSize(it)) }
            .toMutableList()
        )
      }
    }

    if (list.isEmpty()) {
      var abi: Int
      if (specifiedAbi != null) {
        abi = specifiedAbi
      } else {
        abi = getAbi(packageInfo)
        if (abi == NO_LIBS) {
          abi = if (Process.is64Bit()) {
            ARMV8
          } else {
            ARMV7
          }
        }
      }
      list.addAll(getSourceLibs(packageInfo, "lib/${getAbiString(LibCheckerApp.app, abi, false)}"))
    }
    list.addAll(getSourceLibs(packageInfo, "assets/", "/assets"))

    if (needStaticLibrary) {
      list.addAll(getStaticLibs(packageInfo))
    }

    return list.distinctBy { it.name }
  }

  /**
   * Get native libraries of an app from source path
   * @param packageInfo PackageInfo
   * @return List of LibStringItem
   */
  private fun getSourceLibs(
    packageInfo: PackageInfo,
    childDir: String,
    source: String? = null
  ): List<LibStringItem> {
    return runCatching {
      ZipFile(File(packageInfo.applicationInfo.sourceDir)).use { zipFile ->
        return zipFile.entries()
          .asSequence()
          .filter { (it.name.startsWith(childDir)) && it.name.endsWith(".so") }
          .distinctBy { it.name.split("/").last() }
          .map { LibStringItem(it.name.split("/").last(), it.size, source) }
          .toList()
          .ifEmpty { getSplitLibs(packageInfo) }
      }
    }.onFailure {
      Timber.e(it)
    }.getOrElse { emptyList() }
  }

  /**
   * Get native libraries of an app from split apk
   * @param packageInfo PackageInfo
   * @return List of LibStringItem
   */
  private fun getSplitLibs(packageInfo: PackageInfo): List<LibStringItem> {
    val libList = mutableListOf<LibStringItem>()
    val splitList = getSplitsSourceDir(packageInfo)
    if (splitList.isNullOrEmpty()) {
      return listOf()
    }

    splitList.find {
      val fileName = it.split(File.separator).last()
      fileName.startsWith("split_config.arm") || fileName.startsWith("split_config.x86")
    }?.let {
      ZipFile(File(it)).use { zipFile ->
        zipFile.entries().asSequence().forEach { entry ->
          if (entry.name.contains("lib/") && entry.isDirectory.not()) {
            libList.add(LibStringItem(entry.name.split("/").last(), entry.size))
          }
        }
      }
    }

    return libList
  }

  private val regex_splits by lazy { Regex("split_config\\.(.*)\\.apk") }

  fun getSplitsSourceDir(packageInfo: PackageInfo): Array<String>? {
    if (FreezeUtils.isAppFrozen(packageInfo.applicationInfo)) {
      val files = File(packageInfo.applicationInfo.sourceDir).parentFile
      if (files?.exists() == true) {
        return files.listFiles()?.asSequence()
          ?.filter {
            it.name.matches(regex_splits)
          }
          ?.map { it.absolutePath }
          ?.toList()
          ?.toTypedArray()
      }
    }
    return packageInfo.applicationInfo.splitSourceDirs
  }

  /**
   * Check if an app uses split apks
   * @return true if it uses split apks
   */
  fun PackageInfo.isSplitsApk(): Boolean {
    return !applicationInfo.splitSourceDirs.isNullOrEmpty()
  }

  /**
   * Check if an app uses Kotlin language
   * @return true if it uses Kotlin language
   */
  fun PackageInfo.isKotlinUsed(): Boolean {
    return runCatching {
      val file = File(applicationInfo.sourceDir)

      ZipFile(file).use {
        it.getEntry("kotlin-tooling-metadata.json") != null ||
          it.getEntry("kotlin/kotlin.kotlin_builtins") != null ||
          it.getEntry("META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler") != null ||
          it.getEntry("META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory") != null ||
          isKotlinUsedInClassDex(file)
      }
    }.getOrDefault(false)
  }

  const val STATIC_LIBRARY_SOURCE_PREFIX = "[Path] "
  const val VERSION_CODE_PREFIX = "[Version Code] "

  /**
   * Get static libraries which app uses
   * @param packageInfo PackageInfo
   * @return static libraries list
   */
  fun getStaticLibs(packageInfo: PackageInfo): List<LibStringItem> {
    val sharedLibs = packageInfo.applicationInfo.sharedLibraryFiles
    try {
      val demands =
        StaticLibraryReader.getStaticLibrary(File(packageInfo.applicationInfo.sourceDir))
      if (demands.isNullOrEmpty() || sharedLibs.isNullOrEmpty()) {
        return listOf()
      }

      val list = mutableListOf<LibStringItem>()
      demands.forEach {
        val source = sharedLibs.find { shared -> shared.contains(it.key) }
        if (source != null) {
          list.add(
            LibStringItem(
              it.key,
              0L,
              "$STATIC_LIBRARY_SOURCE_PREFIX$source\n$VERSION_CODE_PREFIX${it.value}"
            )
          )
        }
      }
      return list
    } catch (e: Exception) {
      Timber.e(e)
      return emptyList()
    }
  }

  /**
   * Get all meta data in an app
   * @param packageInfo PackageInfo
   * @return meta data list
   */
  fun getMetaDataItems(packageInfo: PackageInfo): List<LibStringItem> {
    val appResources by lazy { SystemServices.packageManager.getResourcesForApplication(packageInfo.applicationInfo) }
    packageInfo.applicationInfo.metaData?.let {
      return it.keySet().asSequence()
        .map { key ->
          var value = it.get(key).toString()
          var id = 0L

          if (value.isNotBlank() && value.isDigitsOnly() && value.toLongOrNull() != null) {
            id = value.toLong()
            if ((id and 0xFF000000) == 0x7F000000.toLong() && (id and 0x00FF0000) >= 0x00010000 && (id and 0x0000FFFF) >= 0x00000000) {
              // This may be an android resource id
              Timber.d("Found android resource id: $key")
              runCatching {
                value = appResources.getResourceName(id.toInt())
              }
            } else {
              id = 0L
            }
          }
          LibStringItem(key, id, value)
        }
        .toList()
    } ?: return emptyList()
  }

  /**
   * Get all permissions in an app
   * @param packageInfo PackageInfo
   * @return permissions list
   */
  fun getPermissionsItems(packageInfo: PackageInfo): List<LibStringItem> {
    return packageInfo.getPermissionsList().asSequence()
      .map { perm -> LibStringItem(perm, 0) }
      .toList()
  }

  /**
   * Check if an app uses Kotlin language from classes.dex
   * @param file APK file of the app
   * @return true if it uses Kotlin language
   */
  private fun isKotlinUsedInClassDex(file: File): Boolean {
    return try {
      FastDexFileFactory.loadDexContainer(file, Opcodes.getDefault()).apply {
        dexEntryNames.forEach { entry ->
          val isKotlinUsage = getEntry(entry)?.dexFile?.classes?.any {
            it.type.startsWith("Lkotlin/") || it.type.startsWith("Lkotlinx/")
          } ?: false
          if (isKotlinUsage) {
            return true
          }
        }
      }
      return false
    } catch (e: Throwable) {
      false
    }
  }

  /**
   * Get components list of an app
   * @param packageName Package name of the app
   * @param type Component type
   * @param isSimpleName Whether to show class name as a simple name
   * @return List of StatefulComponent
   */
  fun getComponentList(
    packageName: String,
    @LibType type: Int,
    isSimpleName: Boolean
  ): List<StatefulComponent> {
    val flag = when (type) {
      SERVICE -> PackageManager.GET_SERVICES
      ACTIVITY -> PackageManager.GET_ACTIVITIES
      RECEIVER -> PackageManager.GET_RECEIVERS
      PROVIDER -> PackageManager.GET_PROVIDERS
      else -> 0
    }

    return runCatching {
      getComponentList(getPackageInfo(packageName, flag), type, isSimpleName)
    }.getOrElse { emptyList() }
  }

  /**
   * Get components list of an app
   * @param packageName Package name of the app
   * @param type Component type
   * @param isSimpleName Whether to show class name as a simple name
   * @return List of String
   */
  fun getComponentStringList(
    packageName: String,
    @LibType type: Int,
    isSimpleName: Boolean
  ): List<String> {
    val flag = when (type) {
      SERVICE -> PackageManager.GET_SERVICES
      ACTIVITY -> PackageManager.GET_ACTIVITIES
      RECEIVER -> PackageManager.GET_RECEIVERS
      PROVIDER -> PackageManager.GET_PROVIDERS
      else -> 0
    }

    return runCatching {
      getComponentStringList(getPackageInfo(packageName, flag), type, isSimpleName)
    }.getOrElse { emptyList() }
  }

  /**
   * Get components list of an app
   * @param packageInfo PackageInfo
   * @param type Component type
   * @param isSimpleName Whether to show class name as a simple name
   * @return List of StatefulComponent
   */
  private fun getComponentList(
    packageInfo: PackageInfo,
    @LibType type: Int,
    isSimpleName: Boolean
  ): List<StatefulComponent> {
    val list: Array<out ComponentInfo>? = when (type) {
      SERVICE -> packageInfo.services
      ACTIVITY -> packageInfo.activities
      RECEIVER -> packageInfo.receivers
      PROVIDER -> packageInfo.providers
      else -> null
    }

    return getComponentList(packageInfo.packageName, list, isSimpleName)
  }

  /**
   * Get components list of an app
   * @param packageInfo PackageInfo
   * @param type Component type
   * @param isSimpleName Whether to show class name as a simple name
   * @return List of String
   */
  private fun getComponentStringList(
    packageInfo: PackageInfo,
    @LibType type: Int,
    isSimpleName: Boolean
  ): List<String> {
    val list: Array<out ComponentInfo>? = when (type) {
      SERVICE -> packageInfo.services
      ACTIVITY -> packageInfo.activities
      RECEIVER -> packageInfo.receivers
      PROVIDER -> packageInfo.providers
      else -> null
    }

    return getComponentStringList(packageInfo.packageName, list, isSimpleName)
  }

  /**
   * Get components list of an app
   * @param packageName Package name of the app
   * @param list List of components(can be nullable)
   * @param isSimpleName Whether to show class name as a simple name
   * @return List of StatefulComponent
   */
  fun getComponentList(
    packageName: String,
    list: Array<out ComponentInfo>?,
    isSimpleName: Boolean
  ): List<StatefulComponent> {
    if (list.isNullOrEmpty()) {
      return emptyList()
    }
    var state: Int
    var isEnabled: Boolean
    return list.asSequence()
      .map {
        state = runCatching {
          SystemServices.packageManager.getComponentEnabledSetting(
            ComponentName(
              packageName,
              it.name
            )
          )
        }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        isEnabled = when (state) {
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
          PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
          PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> it.enabled
          else -> false
        }

        val name = if (isSimpleName) {
          it.name.orEmpty().removePrefix(packageName)
        } else {
          it.name.orEmpty()
        }
        StatefulComponent(name, isEnabled, it.processName.removePrefix(it.packageName))
      }
      .toList()
  }

  /**
   * Get components list of an app
   * @param packageName Package name of the app
   * @param list List of components(can be nullable)
   * @param isSimpleName Whether to show class name as a simple name
   * @return List of String
   */
  private fun getComponentStringList(
    packageName: String,
    list: Array<out ComponentInfo>?,
    isSimpleName: Boolean
  ): List<String> {
    if (list.isNullOrEmpty()) {
      return emptyList()
    }
    return list.asSequence()
      .map {
        if (isSimpleName) {
          it.name.removePrefix(packageName)
        } else {
          it.name
        }
      }
      .toList()
  }

  /**
   * Get ABIs set of an app
   * @param file Application file
   * @param applicationInfo ApplicationInfo
   * @param isApk Whether is an APK file
   * @param overlay Is this app an overlay app
   * @param ignoreArch Ignore arch so you can get all ABIs
   * @return ABI type
   */
  fun getAbiSet(
    file: File,
    applicationInfo: ApplicationInfo,
    isApk: Boolean = false,
    overlay: Boolean,
    ignoreArch: Boolean = false
  ): Set<Int> {
    var elementName: String

    val abiSet = mutableSetOf<Int>()
    var zipFile: ZipFile? = null

    try {
      zipFile = ZipFile(file)
      val entries = zipFile.entries()

      if (overlay) {
        abiSet.add(OVERLAY)
        return abiSet
      }

      var entry: ZipEntry

      while (entries.hasMoreElements()) {
        entry = entries.nextElement()

        if (entry.isDirectory) {
          continue
        }

        elementName = entry.name

        if (abiSet.size == 5) {
          break
        }

        if (elementName.startsWith("lib/")) {
          elementName = elementName.removePrefix("lib/")
          when {
            elementName.startsWith("$ARMV8_STRING/") -> {
              if (Build.SUPPORTED_ABIS.contains(ARMV8_STRING) || ignoreArch) {
                abiSet.add(ARMV8)
              }
            }
            elementName.startsWith("$ARMV7_STRING/") -> {
              if (Build.SUPPORTED_ABIS.contains(ARMV7_STRING) || ignoreArch) {
                abiSet.add(ARMV7)
              }
            }
            elementName.startsWith("$ARMV5_STRING/") -> {
              if (Build.SUPPORTED_ABIS.contains(ARMV5_STRING) || ignoreArch) {
                abiSet.add(ARMV5)
              }
            }
            elementName.startsWith("$X86_64_STRING/") -> {
              if (Build.SUPPORTED_ABIS.contains(X86_64_STRING) || ignoreArch) {
                abiSet.add(X86_64)
              }
            }
            elementName.startsWith("$X86_STRING/") -> {
              if (Build.SUPPORTED_ABIS.contains(X86_STRING) || ignoreArch) {
                abiSet.add(X86)
              }
            }
          }
        }
      }

      if (abiSet.isEmpty()) {
        if (!isApk) {
          abiSet.addAll(getAbiListByNativeDir(applicationInfo.nativeLibraryDir))
        }

        if (abiSet.isEmpty()) {
          abiSet.add(NO_LIBS)
        }
      }
      return abiSet
    } catch (e: Throwable) {
      Timber.e(e)
      abiSet.clear()
      abiSet.add(ERROR)
      return abiSet
    } finally {
      zipFile?.close()
    }
  }

  /**
   * Get ABI type of an app from native path
   * @param nativePath Native path of the app
   * @return ABI type
   */
  private fun getAbiListByNativeDir(nativePath: String): MutableSet<Int> {
    val file = File(nativePath.substring(0, nativePath.lastIndexOf("/")))
    val abis = mutableSetOf<Int>()

    val fileList = file.listFiles() ?: return mutableSetOf()

    fileList.asSequence()
      .forEach {
        if (it.isDirectory) {
          when (it.name) {
            "arm64" -> abis.add(ARMV8)
            "arm" -> abis.add(ARMV7)
            "x86_64" -> abis.add(X86_64)
            "x86" -> abis.add(X86)
          }
        }
      }

    return abis
  }

  /**
   * Get ABI type of an app
   * @param packageInfo PackageInfo
   * @return ABI type
   */
  fun getAbi(
    packageInfo: PackageInfo,
    isApk: Boolean = false,
    abiSet: Set<Int>? = null
  ): Int {
    val applicationInfo: ApplicationInfo = packageInfo.applicationInfo
    val file = File(applicationInfo.sourceDir)
    val use32bitAbi = applicationInfo.isUse32BitAbi()
    val overlay = packageInfo.isOverlay()
    val multiArch = applicationInfo.flags and ApplicationInfo.FLAG_MULTIARCH != 0

    if (overlay) {
      return OVERLAY
    }

    val realAbiSet =
      abiSet ?: getAbiSet(file, applicationInfo, isApk, overlay = false, ignoreArch = true)
    if (realAbiSet.contains(NO_LIBS)) {
      return NO_LIBS
    }

    var abi = when (Refine.unsafeCast<ApplicationInfoHidden>(applicationInfo).primaryCpuAbi) {
      ARMV8_STRING -> ARMV8
      ARMV7_STRING -> ARMV7
      ARMV5_STRING -> ARMV5
      X86_64_STRING -> X86_64
      X86_STRING -> X86
      null -> {
        if (FreezeUtils.isAppFrozen(packageInfo.packageName)) {
          val supportedAbiSet = realAbiSet.toMutableSet()
          realAbiSet.forEach {
            if (Build.SUPPORTED_ABIS.contains(getAbiString(LibCheckerApp.app, it, false))) {
              supportedAbiSet.add(it)
            }
          }
          if (use32bitAbi) {
            supportedAbiSet.remove(ARMV8)
            supportedAbiSet.remove(X86_64)
          }
          when {
            supportedAbiSet.contains(ARMV8) -> ARMV8
            supportedAbiSet.contains(ARMV7) -> ARMV7
            supportedAbiSet.contains(ARMV5) -> ARMV5
            supportedAbiSet.contains(X86_64) -> X86_64
            supportedAbiSet.contains(X86) -> X86
            else -> ERROR
          }
        } else {
          NO_LIBS
        }
      }
      else -> ERROR
    }

    if (multiArch) {
      abi += MULTI_ARCH
    }
    return abi
  }

  private val ABI_STRING_RES_MAP = arrayMapOf(
    ARMV8 to listOf(R.string.arm64_v8a),
    ARMV7 to listOf(R.string.armeabi_v7a),
    ARMV5 to listOf(R.string.armeabi),
    X86_64 to listOf(R.string.x86_64),
    X86 to listOf(R.string.x86),
    NO_LIBS to listOf(R.string.no_libs),
    ERROR to listOf(R.string.cannot_read),
    ARMV8 + MULTI_ARCH to listOf(R.string.arm64_v8a, R.string.multiArch),
    ARMV7 + MULTI_ARCH to listOf(R.string.armeabi_v7a, R.string.multiArch),
    ARMV5 + MULTI_ARCH to listOf(R.string.armeabi, R.string.multiArch),
    X86_64 + MULTI_ARCH to listOf(R.string.x86_64, R.string.multiArch),
    X86 + MULTI_ARCH to listOf(R.string.x86, R.string.multiArch),
  )

  private val ABI_BADGE_MAP = arrayMapOf(
    NO_LIBS to if (Process.is64Bit()) {
      R.drawable.ic_abi_label_64bit
    } else {
      R.drawable.ic_abi_label_32bit
    },
    ERROR to 0,
    ARMV8 to R.drawable.ic_abi_label_64bit,
    X86_64 to R.drawable.ic_abi_label_64bit,
    ARMV7 to R.drawable.ic_abi_label_32bit,
    ARMV5 to R.drawable.ic_abi_label_32bit,
    X86 to R.drawable.ic_abi_label_32bit,
    OVERLAY to R.drawable.ic_abi_label_no_libs,
    ARMV8 + MULTI_ARCH to R.drawable.ic_abi_label_64bit,
    X86_64 + MULTI_ARCH to R.drawable.ic_abi_label_64bit,
    ARMV7 + MULTI_ARCH to R.drawable.ic_abi_label_32bit,
    ARMV5 + MULTI_ARCH to R.drawable.ic_abi_label_32bit,
    X86 + MULTI_ARCH to R.drawable.ic_abi_label_32bit
  )

  /**
   * Get ABI string from ABI type
   * @param context Context
   * @param abi ABI type
   * @param showExtraInfo show "multiArch" etc. if is true
   * @return ABI string
   */
  fun getAbiString(context: Context, abi: Int, showExtraInfo: Boolean): String {
    if (abi == OVERLAY) {
      return Constants.OVERLAY_STRING
    }
    val resList = if (!showExtraInfo && abi >= MULTI_ARCH) {
      ABI_STRING_RES_MAP[abi % MULTI_ARCH] ?: listOf(R.string.unknown)
    } else {
      ABI_STRING_RES_MAP[abi] ?: listOf(R.string.unknown)
    }
    return resList.joinToString { context.getString(it) }
  }

  /**
   * Get ABI badge resource from ABI type
   * @param type ABI type
   * @return Badge resource
   */
  @DrawableRes
  fun getAbiBadgeResource(type: Int): Int {
    return ABI_BADGE_MAP[type] ?: 0
  }

  /**
   * Format size number to string
   * @param item LibStringItem
   * @return String of size number (100KB)
   */
  fun sizeToString(context: Context, item: LibStringItem): String {
    val source = item.source?.let { ", ${item.source}" }.orEmpty()
    return "(${Formatter.formatFileSize(context, item.size)}$source)"
  }

  /**
   * Check if a package contains a class
   * @param sourceFile Source file
   * @param className Class name
   */
  fun hasDexClass(sourceFile: File, className: String): Boolean {
    return runCatching {
      val typeName = "L${className.replace(".", "/")};".replace("*;", "")
      FastDexFileFactory.loadDexContainer(sourceFile, Opcodes.getDefault()).apply {
        dexEntryNames.forEach { entry ->
          val hasDex = getEntry(entry)?.dexFile?.classes
            ?.any {
              if (className.last() == '*') {
                it.type.startsWith(typeName)
              } else {
                it.type == typeName
              }
            } ?: false
          if (hasDex) {
            return true
          }
        }
      }
      return false
    }.getOrDefault(false)
  }

  /**
   * Get part of DEX classes (at most 5 DEX's) of an app
   * @param packageName Package name
   * @param isApk True if it is an apk file
   * @return List of LibStringItem
   */
  fun getDexList(packageName: String, isApk: Boolean = false): List<LibStringItem> {

    try {
      val path = if (isApk) {
        packageName
      } else {
        getPackageInfo(packageName).applicationInfo.sourceDir
      }

      if (path.isNullOrEmpty()) {
        return emptyList()
      }
      var className: String

      val primaryList = mutableListOf<LibStringItem>()
      val pkgType = "L${packageName.replace(".", "/")}"
      FastDexFileFactory.loadDexContainer(File(path), Opcodes.getDefault()).apply {
        dexEntryNames.forEachIndexed { index, entry ->
          if (index >= 5) {
            return@forEachIndexed
          }
          getEntry(entry)?.let { dexEntry ->
            primaryList += dexEntry.dexFile.classes
              .asSequence()
              .filter { !it.type.startsWith(pkgType) }
              .map { item ->
                className = item.type.substring(1, item.length - 1).replace("/", ".")
                when {
                  // Remove obfuscated classes
                  !className.contains(".") -> LibStringItem("")
                  // Remove kotlin
                  className.startsWith("kotlin") -> LibStringItem("")
                  // Merge AndroidX classes
                  className.startsWith("androidx") -> LibStringItem(
                    className.substring(
                      0,
                      className.indexOf(
                        ".",
                        9
                      )
                    )
                  )
                  // Filter classes which paths deep level greater than 4
                  else -> LibStringItem(
                    className.split(".").run {
                      this.subList(0, this.size.coerceAtMost(4)).joinToString(separator = ".")
                    }
                  )
                }
              }
              .toSet()
              .filter { it.name.isNotBlank() }
              .toMutableList()
          }
        }
      }

      // Merge path deep level 3 classes
      primaryList.filter { it.name.split(".").size == 3 }.forEach {
        primaryList.removeAll { item -> item.name.startsWith(it.name) }
        primaryList.add(it)
      }
      // Merge path deep level 4 classes
      var pathLevel3Item: String
      var filter: List<LibStringItem>
      primaryList.filter { it.name.split(".").size == 4 }.forEach {
        if (DexLibMap.DEEP_LEVEL_3_SET.contains(it.name)) {
          return@forEach
        }

        pathLevel3Item = it.name.split(".").subList(0, 3).joinToString(separator = ".")
        filter = primaryList.filter { item -> item.name.startsWith(pathLevel3Item) }

        if (filter.isNotEmpty()) {
          primaryList.removeAll(filter)
          primaryList.add(LibStringItem(pathLevel3Item))
        }
      }
      return primaryList
    } catch (e: Exception) {
      Timber.e(e)
      return emptyList()
    }
  }

  /**
   * Get permissions of an application
   * @param packageName Package name of the app
   * @return Permissions list
   */
  fun getPermissionsList(packageName: String): List<String> {
    return runCatching {
      getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).getPermissionsList()
    }.getOrElse { emptyList() }
  }

  fun PackageInfo.getPermissionsList(): List<String> {
    return requestedPermissions?.toList() ?: emptyList()
  }

  /**
   * Check if an app is installed
   * @return true if it is installed
   */
  fun isAppInstalled(pkgName: String): Boolean {
    return runCatching {
      SystemServices.packageManager.getApplicationInfo(pkgName, 0).enabled
    }.getOrDefault(false)
  }

  private const val minSdkVersion = "minSdkVersion"

  /**
   * Get minSdkVersion of an app
   * @param packageInfo PackageInfo
   * @return minSdkVersion
   */
  fun getMinSdkVersion(packageInfo: PackageInfo): Int {
    val minSdkVersionValue = if (OsUtils.atLeastN()) {
      packageInfo.applicationInfo.minSdkVersion
    } else {
      val demands = ManifestReader.getManifestProperties(
        File(packageInfo.applicationInfo.sourceDir),
        arrayOf(minSdkVersion)
      )
      demands[minSdkVersion]?.toString()?.toInt() ?: 0
    }
    return minSdkVersionValue
  }

  /**
   * Get minSdkVersion of an app
   * @param packageInfo PackageInfo
   * @return minSdkVersion
   */
  fun getMinSdkVersionString(packageInfo: PackageInfo): String {
    return "$minSdkVersion ${getMinSdkVersion(packageInfo)}"
  }

  private const val AGP_KEYWORD = "androidGradlePluginVersion"
  private const val AGP_KEYWORD2 = "Created-By: Android Gradle "

  fun getAGPVersion(packageInfo: PackageInfo): String? {
    runCatching {
      ZipFile(File(packageInfo.applicationInfo.sourceDir)).use { zipFile ->
        zipFile.getEntry("META-INF/com/android/build/gradle/app-metadata.properties")?.let { ze ->
          Properties().apply {
            load(zipFile.getInputStream(ze))
            getProperty(AGP_KEYWORD)?.let {
              return it
            }
          }
        }
        zipFile.getEntry("META-INF/MANIFEST.MF")?.let { ze ->
          zipFile.getInputStream(ze).source().buffer().use {
            while (true) {
              it.readUtf8Line()?.let { line ->
                if (line.startsWith(AGP_KEYWORD2)) {
                  return line.removePrefix(AGP_KEYWORD2)
                }
              } ?: break
            }
          }
        }
        arrayOf(
          "META-INF/androidx.databinding_viewbinding.version",
          "META-INF/androidx.databinding_databindingKtx.version",
          "META-INF/androidx.databinding_library.version"
        ).forEach { entry ->
          zipFile.getEntry(entry)?.let { ze ->
            zipFile.getInputStream(ze).source().buffer().use { bs ->
              return bs.readUtf8Line().takeIf { it?.isNotBlank() == true }
            }
          }
        }
      }
    }

    return null
  }

  private val runtime by lazy { Runtime.getRuntime() }

  private fun getAppListByShell(): List<String> {
    try {
      val pmList = mutableListOf<String>()
      val process = runtime.exec("pm list packages")
      process.inputStream.source().buffer().use { bs ->
        while (true) {
          bs.readUtf8Line()?.trim()?.let { line ->
            if (line.startsWith("package:")) {
              line.removePrefix("package:").takeIf { removedPrefix -> removedPrefix.isNotBlank() }
                ?.let { pmList.add(it) }
            }
          } ?: break
        }
      }
      return pmList
    } catch (t: Throwable) {
      Timber.w(t)
      return emptyList()
    }
  }

  suspend fun getAppsList(): List<PackageInfo> {
    var appList: List<PackageInfo>
    var retry: Boolean

    do {
      retry = false
      appList = try {
        getInstallApplications()
      } catch (e: Exception) {
        Timber.w(e)
        delay(200)
        retry = true
        emptyList()
      }.also { items ->
        AppItemRepository.allPackageInfoMap = items.asSequence()
          .map { it.packageName to it }
          .toMap()
      }
    } while (retry)

    val pmList = getAppListByShell()
    try {
      if (pmList.size > appList.size) {
        appList = pmList.asSequence()
          .map { getPackageInfo(it, PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS) }
          .toList()
      }
    } catch (t: Throwable) {
      Timber.w(t)
      appList = emptyList()
    }
    return appList
  }

  fun getPackageSize(packageInfo: PackageInfo, includeSplits: Boolean): Long {
    var size: Long = FileUtils.getFileSize(packageInfo.applicationInfo.sourceDir)

    if (!includeSplits) {
      return size
    }

    getSplitsSourceDir(packageInfo)?.forEach {
      runCatching {
        size += FileUtils.getFileSize(it)
      }
    }
    return size
  }

  fun PackageInfo.isXposedModule(): Boolean {
    return applicationInfo.metaData?.getBoolean("xposedmodule") == true ||
      applicationInfo.metaData?.containsKey("xposedminversion") == true
  }

  fun PackageInfo.isPlayAppSigning(): Boolean {
    return applicationInfo.metaData?.getString("com.android.stamp.type") == "STAMP_TYPE_DISTRIBUTION_APK" &&
      applicationInfo.metaData?.getString("com.android.stamp.source") == "https://play.google.com/store"
  }

  fun PackageInfo.isPWA(): Boolean {
    return applicationInfo.metaData?.keySet()
      ?.any { it.startsWith("org.chromium.webapk.shell_apk") } == true
  }

  fun PackageInfo.isOverlay(): Boolean {
    return try {
      Refine.unsafeCast<PackageInfoHidden>(this).isOverlayPackage
    } catch (t: Throwable) {
      val demands =
        ManifestReader.getManifestProperties(File(applicationInfo.sourceDir), arrayOf("overlay"))
      return demands["overlay"] as? Boolean ?: false
    }
  }

  fun ApplicationInfo.isUse32BitAbi(): Boolean {
    runCatching {
      val demands = ManifestReader.getManifestProperties(File(sourceDir), arrayOf("use32bitAbi"))
      return demands["use32bitAbi"] as? Boolean ?: false
    }.getOrNull() ?: return false
  }

  fun getKotlinPluginVersion(packageInfo: PackageInfo): String? {
    return runCatching {
      ZipFile(packageInfo.applicationInfo.sourceDir).use { zip ->
        val entry = zip.getEntry("kotlin-tooling-metadata.json") ?: return@runCatching null
        zip.getInputStream(entry).source().buffer().use {
          val json = it.readUtf8().fromJson<KotlinToolingMetadata>()
          return json?.buildPluginVersion.takeIf { json?.buildPlugin == "org.jetbrains.kotlin.gradle.plugin.KotlinAndroidPluginWrapper" }
        }
      }
    }.getOrNull()
  }

  fun PackageInfo.isUseJetpackCompose(): Boolean {
    return runCatching {
      hasDexClass(File(applicationInfo.sourceDir), "androidx.compose.*")
    }.getOrDefault(false)
  }
}
