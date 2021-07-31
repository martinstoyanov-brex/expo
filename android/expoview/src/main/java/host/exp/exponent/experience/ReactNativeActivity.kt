// Copyright 2015-present 650 Industries. All rights reserved.
package host.exp.exponent.experience

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.UiThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.facebook.infer.annotation.Assertions
import com.facebook.internal.BundleJSONConverter
import com.facebook.react.devsupport.DoubleTapReloadRecognizer
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import de.greenrobot.event.EventBus
import expo.modules.core.interfaces.Package
import expo.modules.updates.manifest.raw.RawManifest
import host.exp.exponent.Constants
import host.exp.exponent.ExponentManifest
import host.exp.exponent.RNObject
import host.exp.exponent.analytics.Analytics
import host.exp.exponent.analytics.EXL
import host.exp.exponent.di.NativeModuleDepsProvider
import host.exp.exponent.experience.BaseExperienceActivity.ExperienceContentLoaded
import host.exp.exponent.experience.splashscreen.LoadingView
import host.exp.exponent.kernel.*
import host.exp.exponent.kernel.KernelConstants.AddedExperienceEventEvent
import host.exp.exponent.kernel.services.ErrorRecoveryManager
import host.exp.exponent.kernel.services.ExpoKernelServiceRegistry
import host.exp.exponent.notifications.ExponentNotification
import host.exp.exponent.storage.ExponentSharedPreferences
import host.exp.exponent.utils.ExperienceActivityUtils
import host.exp.exponent.utils.ScopedPermissionsRequester
import host.exp.expoview.Exponent
import host.exp.expoview.Exponent.InstanceManagerBuilderProperties
import host.exp.expoview.Exponent.StartReactInstanceDelegate
import host.exp.expoview.R
import org.json.JSONException
import org.json.JSONObject
import versioned.host.exp.exponent.ExponentPackage
import java.util.*
import javax.inject.Inject

abstract class ReactNativeActivity :
  AppCompatActivity(),
  DefaultHardwareBackBtnHandler,
  PermissionAwareActivity {
  class ExperienceDoneLoadingEvent internal constructor(val activity: Activity)

  open fun initialProps(expBundle: Bundle?): Bundle? {
    return expBundle
  }

  protected open fun onDoneLoading() {}

  // Will be called after waitForDrawOverOtherAppPermission
  protected open fun startReactInstance() {}

  protected var reactInstanceManager: RNObject =
    RNObject("com.facebook.react.ReactInstanceManager")
  protected var isCrashed = false
  protected var manifestUrl: String? = null
  var experienceKey: ExperienceKey? = null
  protected var mSDKVersion: String? = null
  protected var activityId = 0

  // In detach we want UNVERSIONED most places. We still need the numbered sdk version
  // when creating cache keys.
  protected var detachSdkVersion: String? = null
  protected lateinit var reactRootView: RNObject
  private lateinit var doubleTapReloadRecognizer: DoubleTapReloadRecognizer
  var isLoading = true
    protected set
  protected var jsBundlePath: String? = null
  protected var manifest: RawManifest? = null
  var isInForeground = false
    protected set
  private var scopedPermissionsRequester: ScopedPermissionsRequester? = null

  @Inject
  protected lateinit var exponentSharedPreferences: ExponentSharedPreferences

  @Inject
  lateinit var expoKernelServiceRegistry: ExpoKernelServiceRegistry

  private lateinit var containerView: FrameLayout

  /**
   * This view is optional and available only when the app runs in Expo Go.
   */
  private var loadingView: LoadingView? = null
  private lateinit var reactContainerView: FrameLayout
  private val handler = Handler()

  protected open fun shouldCreateLoadingView(): Boolean {
    return !Constants.isStandaloneApp() || Constants.SHOW_LOADING_VIEW_IN_SHELL_APP
  }

  val rootView: View?
    get() = reactRootView.get() as View?

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)
    containerView = FrameLayout(this)
    setContentView(containerView)
    reactContainerView = FrameLayout(this)
    containerView.addView(reactContainerView)
    if (shouldCreateLoadingView()) {
      containerView.setBackgroundColor(
        ContextCompat.getColor(
          this,
          R.color.splashscreen_background
        )
      )
      loadingView = LoadingView(this)
      loadingView!!.show()
      containerView.addView(loadingView)
    }
    doubleTapReloadRecognizer = DoubleTapReloadRecognizer()
    Exponent.initialize(this, application)
    NativeModuleDepsProvider.getInstance().inject(ReactNativeActivity::class.java, this)

    // Can't call this here because subclasses need to do other initialization
    // before their listener methods are called.
    // EventBus.getDefault().registerSticky(this);
  }

  protected fun setReactRootView(reactRootView: View) {
    reactContainerView.removeAllViews()
    addReactViewToContentContainer(reactRootView)
  }

  fun addReactViewToContentContainer(reactView: View) {
    if (reactView.parent != null) {
      (reactView.parent as ViewGroup).removeView(reactView)
    }
    reactContainerView.addView(reactView)
  }

  fun hasReactView(reactView: View): Boolean {
    return reactView.parent === reactContainerView
  }

  protected fun hideLoadingView() {
    if (loadingView != null) {
      val viewGroup = loadingView!!.parent as ViewGroup?
      viewGroup?.removeView(loadingView)
      loadingView!!.hide()
      loadingView = null
    }
  }

  protected fun removeAllViewsFromContainer() {
    containerView.removeAllViews()
  }
  // region Loading
  /**
   * Successfully finished loading
   */
  @UiThread
  protected fun finishLoading() {
    waitForReactAndFinishLoading()
  }

  /**
   * There was an error during loading phase
   */
  protected fun interruptLoading() {
    handler.removeCallbacksAndMessages(null)
  }

  // Loop until a view is added to the ReactRootView and once it happens run callback
  private fun waitForReactRootViewToHaveChildrenAndRunCallback(callback: Runnable) {
    if (reactRootView.isNull) {
      return
    }
    if (reactRootView.call("getChildCount") as Int > 0) {
      callback.run()
    } else {
      handler.postDelayed(
        { waitForReactRootViewToHaveChildrenAndRunCallback(callback) },
        VIEW_TEST_INTERVAL_MS
      )
    }
  }

  /**
   * Waits for JS side of React to be launched and then performs final launching actions.
   */
  private fun waitForReactAndFinishLoading() {
    if (Constants.isStandaloneApp() && Constants.SHOW_LOADING_VIEW_IN_SHELL_APP) {
      val layoutParams = containerView.layoutParams
      layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT
      containerView.layoutParams = layoutParams
    }
    waitForReactRootViewToHaveChildrenAndRunCallback {
      onDoneLoading()
      try {
        ExperienceActivityUtils.setRootViewBackgroundColor(manifest, rootView)
      } catch (e: Exception) {
        EXL.e(TAG, e)
      }
      ErrorRecoveryManager.getInstance(experienceKey!!).markExperienceLoaded()
      pollForEventsToSendToRN()
      EventBus.getDefault().post(ExperienceDoneLoadingEvent(this))
      isLoading = false
    }
  }
  // endregion
  // region SplashScreen
  /**
   * Get what version (among versioned classes) of ReactRootView.class SplashScreen module should be looking for.
   */
  protected fun getRootViewClass(manifest: RawManifest): Class<out ViewGroup> {
    val reactRootViewRNClass = reactRootView.rnClass()
    if (reactRootViewRNClass != null) {
      return reactRootViewRNClass as Class<out ViewGroup>
    }
    var sdkVersion = manifest.getSDKVersionNullable()
    if (Constants.TEMPORARY_ABI_VERSION != null && Constants.TEMPORARY_ABI_VERSION == mSDKVersion) {
      sdkVersion = RNObject.UNVERSIONED
    }
    sdkVersion = if (Constants.isStandaloneApp()) RNObject.UNVERSIONED else sdkVersion
    return RNObject("com.facebook.react.ReactRootView").loadVersion(sdkVersion).rnClass() as Class<out ViewGroup>
  }

  // endregion
  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    if (reactInstanceManager.isNotNull && !isCrashed) {
      if (devSupportManager.call("getDevSupportEnabled") as Boolean) {
        val didDoubleTapR = Assertions.assertNotNull(doubleTapReloadRecognizer)
          .didDoubleTapR(keyCode, currentFocus)
        if (didDoubleTapR) {
          devSupportManager.call("reloadExpoApp")
          return true
        }
      }
    }
    return super.onKeyUp(keyCode, event)
  }

  override fun onBackPressed() {
    if (reactInstanceManager.isNotNull && !isCrashed) {
      reactInstanceManager.call("onBackPressed")
    } else {
      super.onBackPressed()
    }
  }

  override fun invokeDefaultOnBackPressed() {
    super.onBackPressed()
  }

  override fun onPause() {
    super.onPause()
    if (reactInstanceManager.isNotNull && !isCrashed) {
      reactInstanceManager.onHostPause()
      // TODO: use onHostPause(activity)
    }
  }

  override fun onResume() {
    super.onResume()
    if (reactInstanceManager.isNotNull && !isCrashed) {
      reactInstanceManager.onHostResume(this, this)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    destroyReactInstanceManager()
    handler.removeCallbacksAndMessages(null)
    EventBus.getDefault().unregister(this)
  }

  public override fun onNewIntent(intent: Intent) {
    if (reactInstanceManager.isNotNull && !isCrashed) {
      try {
        reactInstanceManager.call("onNewIntent", intent)
      } catch (e: Throwable) {
        EXL.e(TAG, e.toString())
        super.onNewIntent(intent)
      }
    } else {
      super.onNewIntent(intent)
    }
  }

  open val isDebugModeEnabled: Boolean
    get() = manifest != null && manifest!!.isDevelopmentMode()

  protected open fun destroyReactInstanceManager() {
    if (reactInstanceManager.isNotNull && !isCrashed) {
      reactInstanceManager.call("destroy")
    }
  }

  public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    Exponent.instance.onActivityResult(requestCode, resultCode, data)
    if (reactInstanceManager.isNotNull && !isCrashed) {
      reactInstanceManager.call("onActivityResult", this, requestCode, resultCode, data)
    }

    // Have permission to draw over other apps. Resume loading.
    if (requestCode == KernelConstants.OVERLAY_PERMISSION_REQUEST_CODE) {
      // startReactInstance() checks isInForeground and onActivityResult is called before onResume,
      // so manually set this here.
      isInForeground = true
      startReactInstance()
    }
  }

  fun startReactInstance(
    delegate: StartReactInstanceDelegate,
    intentUri: String?,
    sdkVersion: String?,
    notification: ExponentNotification?,
    isShellApp: Boolean,
    extraNativeModules: List<Any>?,
    extraExpoPackages: List<Package>?,
    progressListener: DevBundleDownloadProgressListener
  ): RNObject {
    if (isCrashed || !delegate.isInForeground) {
      // Can sometimes get here after an error has occurred. Return early or else we'll hit
      // a null pointer at mReactRootView.startReactApplication
      return RNObject("com.facebook.react.ReactInstanceManager")
    }
    val experienceProperties = mapOf<String, Any?>(
      KernelConstants.MANIFEST_URL_KEY to manifestUrl,
      KernelConstants.LINKING_URI_KEY to linkingUri,
      KernelConstants.INTENT_URI_KEY to intentUri,
      KernelConstants.IS_HEADLESS_KEY to false
    )
    val instanceManagerBuilderProperties = InstanceManagerBuilderProperties()
    instanceManagerBuilderProperties.application = application
    instanceManagerBuilderProperties.jsBundlePath = jsBundlePath
    instanceManagerBuilderProperties.experienceProperties = experienceProperties
    instanceManagerBuilderProperties.expoPackages = extraExpoPackages
    instanceManagerBuilderProperties.exponentPackageDelegate = delegate.exponentPackageDelegate
    instanceManagerBuilderProperties.manifest = manifest
    instanceManagerBuilderProperties.singletonModules = ExponentPackage.getOrCreateSingletonModules(
      applicationContext, manifest, extraExpoPackages
    )
    val versionedUtils = RNObject("host.exp.exponent.VersionedUtils").loadVersion(sdkVersion)
    val builder = versionedUtils.callRecursive(
      "getReactInstanceManagerBuilder",
      instanceManagerBuilderProperties
    )

    // This used to not be called prior to SDK 36
    builder.call("setCurrentActivity", this)

    // ReactNativeInstance is considered to be resumed when it has its activity attached, which is expected to be the case here
    builder.call(
      "setInitialLifecycleState",
      RNObject.versionedEnum(sdkVersion, "com.facebook.react.common.LifecycleState", "RESUMED")
    )
    if (extraNativeModules != null) {
      for (nativeModule in extraNativeModules) {
        builder.call("addPackage", nativeModule)
      }
    }
    if (delegate.isDebugModeEnabled) {
      val debuggerHost = manifest!!.getDebuggerHost()
      val mainModuleName = manifest!!.getMainModuleName()
      Exponent.enableDeveloperSupport(debuggerHost, mainModuleName, builder)
      val devLoadingView =
        RNObject("com.facebook.react.devsupport.DevLoadingViewController").loadVersion(sdkVersion)
      devLoadingView.callRecursive("setDevLoadingEnabled", false)
      val devBundleDownloadListener =
        RNObject("host.exp.exponent.ExponentDevBundleDownloadListener")
          .loadVersion(sdkVersion)
          .construct(progressListener)
      builder.callRecursive("setDevBundleDownloadListener", devBundleDownloadListener.get())
    } else {
      waitForReactAndFinishLoading()
    }
    val bundle = Bundle()
    val exponentProps = JSONObject()
    if (notification != null) {
      bundle.putString("notification", notification.body) // Deprecated
      try {
        exponentProps.put("notification", notification.toJSONObject("selected"))
      } catch (e: JSONException) {
        e.printStackTrace()
      }
    }
    try {
      exponentProps.put("manifestString", manifest.toString())
      exponentProps.put("shell", isShellApp)
      exponentProps.put("initialUri", intentUri)
    } catch (e: JSONException) {
      EXL.e(TAG, e)
    }
    val metadata = exponentSharedPreferences.getExperienceMetadata(experienceKey)
    if (metadata != null) {
      // TODO: fix this. this is the only place that EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS is sent to the experience,
      // we need to sent them with the standard notification events so that you can get all the unread notification through an event
      // Copy unreadNotifications into exponentProps
      if (metadata.has(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS)) {
        try {
          val unreadNotifications =
            metadata.getJSONArray(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS)
          delegate.handleUnreadNotifications(unreadNotifications)
        } catch (e: JSONException) {
          e.printStackTrace()
        }
        metadata.remove(ExponentSharedPreferences.EXPERIENCE_METADATA_UNREAD_REMOTE_NOTIFICATIONS)
      }
      exponentSharedPreferences.updateExperienceMetadata(experienceKey, metadata)
    }
    try {
      bundle.putBundle("exp", BundleJSONConverter.convertToBundle(exponentProps))
    } catch (e: JSONException) {
      throw Error("JSONObject failed to be converted to Bundle", e)
    }
    if (!delegate.isInForeground) {
      return RNObject("com.facebook.react.ReactInstanceManager")
    }
    Analytics.markEvent(Analytics.TimedEvent.STARTED_LOADING_REACT_NATIVE)
    val mReactInstanceManager = builder.callRecursive("build")
    val devSettings =
      mReactInstanceManager.callRecursive("getDevSupportManager").callRecursive("getDevSettings")
    if (devSettings != null) {
      devSettings.setField("exponentActivityId", activityId)
      if (devSettings.call("isRemoteJSDebugEnabled") as Boolean) {
        waitForReactAndFinishLoading()
      }
    }
    mReactInstanceManager.onHostResume(this, this)
    val appKey = manifest!!.getAppKey()
    reactRootView.call(
      "startReactApplication",
      mReactInstanceManager.get(),
      appKey ?: KernelConstants.DEFAULT_APPLICATION_KEY,
      initialProps(bundle)
    )
    return mReactInstanceManager
  }

  protected fun shouldShowErrorScreen(errorMessage: ExponentErrorMessage): Boolean {
    if (isLoading) {
      // Don't hit ErrorRecoveryManager until bridge is initialized.
      // This is the same on iOS.
      return true
    }
    val errorRecoveryManager = ErrorRecoveryManager.getInstance(experienceKey!!)
    errorRecoveryManager.markErrored()
    return if (errorRecoveryManager.shouldReloadOnError()) {
      if (!KernelProvider.instance.reloadVisibleExperience(manifestUrl!!)) {
        // Kernel couldn't reload, show error screen
        return true
      }
      errorQueue.clear()
      try {
        val eventProperties = JSONObject()
        eventProperties.put(Analytics.USER_ERROR_MESSAGE, errorMessage.userErrorMessage())
        eventProperties.put(Analytics.DEVELOPER_ERROR_MESSAGE, errorMessage.developerErrorMessage())
        eventProperties.put(Analytics.MANIFEST_URL, manifestUrl)
        Analytics.logEvent(Analytics.ERROR_RELOADED, eventProperties)
      } catch (e: Exception) {
        EXL.e(TAG, e.message)
      }
      false
    } else {
      true
    }
  }

  fun onEventMainThread(event: AddedExperienceEventEvent) {
    if (manifestUrl != null && manifestUrl == event.manifestUrl) {
      pollForEventsToSendToRN()
    }
  }

  fun onEvent(event: ExperienceContentLoaded?) {}

  private fun pollForEventsToSendToRN() {
    if (manifestUrl == null) {
      return
    }
    try {
      val rctDeviceEventEmitter =
        RNObject("com.facebook.react.modules.core.DeviceEventManagerModule\$RCTDeviceEventEmitter")
      rctDeviceEventEmitter.loadVersion(detachSdkVersion)
      val existingEmitter = reactInstanceManager.callRecursive("getCurrentReactContext")
        .callRecursive("getJSModule", rctDeviceEventEmitter.rnClass())
      if (existingEmitter != null) {
        val events = KernelProvider.instance.consumeExperienceEvents(
          manifestUrl!!
        )
        for ((eventName, eventPayload) in events) {
          existingEmitter.call("emit", eventName, eventPayload)
        }
      }
    } catch (e: Throwable) {
      EXL.e(TAG, e)
    }
  }

  // for getting global permission
  override fun checkSelfPermission(permission: String): Int {
    return super.checkPermission(permission, Process.myPid(), Process.myUid())
  }

  override fun shouldShowRequestPermissionRationale(permission: String): Boolean {
    // in scoped application we don't have `don't ask again` button
    return if (!Constants.isStandaloneApp() && checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
      true
    } else super.shouldShowRequestPermissionRationale(permission)
  }

  override fun requestPermissions(
    permissions: Array<String>,
    requestCode: Int,
    listener: PermissionListener
  ) {
    if (requestCode == ScopedPermissionsRequester.EXPONENT_PERMISSIONS_REQUEST) {
      val name = manifest!!.getName()
      scopedPermissionsRequester = ScopedPermissionsRequester(experienceKey)
      scopedPermissionsRequester!!.requestPermissions(this, name ?: "", permissions, listener)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      super.requestPermissions(permissions, requestCode)
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    if (requestCode == ScopedPermissionsRequester.EXPONENT_PERMISSIONS_REQUEST) {
      if (permissions.isNotEmpty() && grantResults.size == permissions.size && scopedPermissionsRequester != null) {
        if (scopedPermissionsRequester!!.onRequestPermissionsResult(permissions, grantResults)) {
          scopedPermissionsRequester = null
        }
      }
    } else {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
  }

  // for getting scoped permission
  override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
    val globalResult = super.checkPermission(permission, pid, uid)
    return expoKernelServiceRegistry.permissionsKernelService.getPermissions(
      globalResult,
      packageManager,
      permission,
      experienceKey!!
    )
  }

  val devSupportManager: RNObject
    get() = reactInstanceManager.callRecursive("getDevSupportManager")

  // deprecated in favor of Expo.Linking.makeUrl
  // TODO: remove this
  private val linkingUri: String?
    get() = if (Constants.SHELL_APP_SCHEME != null) {
      Constants.SHELL_APP_SCHEME + "://"
    } else {
      val uri = Uri.parse(manifestUrl)
      val host = uri.host
      if (host != null && (
        host == "exp.host" || host == "expo.io" || host == "exp.direct" || host == "expo.test" ||
          host.endsWith(".exp.host") || host.endsWith(".expo.io") || host.endsWith(".exp.direct") || host.endsWith(
            ".expo.test"
          )
        )
      ) {
        val pathSegments = uri.pathSegments
        val builder = uri.buildUpon()
        builder.path(null)
        for (segment in pathSegments) {
          if (ExponentManifest.DEEP_LINK_SEPARATOR == segment) {
            break
          }
          builder.appendEncodedPath(segment)
        }
        builder.appendEncodedPath(ExponentManifest.DEEP_LINK_SEPARATOR_WITH_SLASH).build()
          .toString()
      } else {
        manifestUrl
      }
    }

  companion object {
    private val TAG = ReactNativeActivity::class.java.simpleName
    private const val VIEW_TEST_INTERVAL_MS: Long = 20
    @JvmStatic protected var errorQueue: Queue<ExponentError> = LinkedList()
  }
}
