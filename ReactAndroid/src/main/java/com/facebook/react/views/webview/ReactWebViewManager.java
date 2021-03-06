/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 * <p>
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.views.webview;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Picture;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.support.v4.view.ViewCompat;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.UiThreadUtil;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.webview.events.TopLoadingErrorEvent;
import com.facebook.react.views.webview.events.TopLoadingFinishEvent;
import com.facebook.react.views.webview.events.TopLoadingStartEvent;
import com.facebook.react.views.webview.events.TopMessageEvent;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Manages instances of {@link WebView}
 * <p>
 * Can accept following commands:
 * - GO_BACK
 * - GO_FORWARD
 * - RELOAD
 * <p>
 * {@link WebView} instances could emit following direct events:
 * - topLoadingFinish
 * - topLoadingStart
 * - topLoadingError
 * <p>
 * Each event will carry the following properties:
 * - target - view's react tag
 * - url - url set for the webview
 * - loading - whether webview is in a loading state
 * - title - title of the current page
 * - canGoBack - boolean, whether there is anything on a history stack to go back
 * - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
@ReactModule(name = ReactWebViewManager.REACT_CLASS)
public class ReactWebViewManager extends SimpleViewManager<WebView> {
    
    protected static final String REACT_CLASS = "RCTWebView";
    
    private static final String HTML_ENCODING = "UTF-8";
    private static final String HTML_MIME_TYPE = "text/html; charset=utf-8";
    private static final String BRIDGE_NAME = "__REACT_WEB_VIEW_BRIDGE";
    
    private static final String HTTP_METHOD_POST = "POST";
    
    public static final int COMMAND_GO_BACK = 1;
    public static final int COMMAND_GO_FORWARD = 2;
    public static final int COMMAND_RELOAD = 3;
    public static final int COMMAND_STOP_LOADING = 4;
    public static final int COMMAND_POST_MESSAGE = 5;
    public static final int COMMAND_INJECT_JAVASCRIPT = 6;
    
    // Use `webView.loadUrl("about:blank")` to reliably reset the view
    // state and release page resources (including any running JavaScript).
    private static final String BLANK_URL = "about:blank";
    
    public static final int INPUT_FILE_REQUEST_CODE = 1001;
    public static final String EXTRA_FROM_NOTIFICATION = "EXTRA_FROM_NOTIFICATION";
    private WebViewConfig mWebViewConfig;
    
    @Nullable
    WebView.PictureListener mPictureListener;
    private ValueCallback<Uri[]> mFilePathCallback;
    private String mCameraPhotoPath;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private View mVideoView;
    //    private View mWebView;
    
    private final FrameLayout.LayoutParams FULLSCREEN_LAYOUT_PARAMS = new FrameLayout.LayoutParams(
                                                                                                   LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER);
    
    
    protected static class ReactWebViewClient extends WebViewClient {
        
        private boolean mLastLoadFailed = false;
        
        @Override
        public void onPageFinished(WebView webView, String url) {
            super.onPageFinished(webView, url);
            
            
            if (!mLastLoadFailed) {
                ReactWebView reactWebView = (ReactWebView) webView;
                reactWebView.callInjectedJavaScript();
                reactWebView.linkBridge();
                emitFinishEvent(webView, url);
            }
        }
        
        @Override
        public void onPageStarted(WebView webView, String url, Bitmap favicon) {
            super.onPageStarted(webView, url, favicon);
            mLastLoadFailed = false;
            
            dispatchEvent(
                          webView,
                          new TopLoadingStartEvent(
                                                   webView.getId(),
                                                   createWebViewEvent(webView, url)));
        }
        
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            
//            //url有包含learningdigital.com,不進行攔截,其他超連結開啟外部瀏覽器
//            if (url.toLowerCase().contains("learningdigital.com") ||
//                url.startsWith("file://")) {
//                return false;
//            } else {
//                
//                if(!url.startsWith("market://")) {
//                    try {
//                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
//                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//                        view.getContext().startActivity(intent);
//                    } catch (ActivityNotFoundException e) {
//                        FLog.w(ReactConstants.TAG, "activity not found to handle uri scheme for: " + url, e);
//                    }
//                }
//                return true;
//            }
            
            if (url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("file://")) {
                return false;
            } else {
                
                if(!url.startsWith("market://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        view.getContext().startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        FLog.w(ReactConstants.TAG, "activity not found to handle uri scheme for: " + url, e);
                    }
                }
                return true;
            }
        }
        
        @Override
        public void onReceivedError(
                                    WebView webView,
                                    int errorCode,
                                    String description,
                                    String failingUrl) {
            super.onReceivedError(webView, errorCode, description, failingUrl);
            mLastLoadFailed = true;
            
            // In case of an error JS side expect to get a finish event first, and then get an error event
            // Android WebView does it in the opposite way, so we need to simulate that behavior
            emitFinishEvent(webView, failingUrl);
            
            WritableMap eventData = createWebViewEvent(webView, failingUrl);
            eventData.putDouble("code", errorCode);
            eventData.putString("description", description);
            
            dispatchEvent(
                          webView,
                          new TopLoadingErrorEvent(webView.getId(), eventData));
        }
        
        @Override
        public void doUpdateVisitedHistory(WebView webView, String url, boolean isReload) {
            super.doUpdateVisitedHistory(webView, url, isReload);
            
            dispatchEvent(
                          webView,
                          new TopLoadingStartEvent(
                                                   webView.getId(),
                                                   createWebViewEvent(webView, url)));
        }
        
        private void emitFinishEvent(WebView webView, String url) {
            dispatchEvent(
                          webView,
                          new TopLoadingFinishEvent(
                                                    webView.getId(),
                                                    createWebViewEvent(webView, url)));
        }
        
        private WritableMap createWebViewEvent(WebView webView, String url) {
            WritableMap event = Arguments.createMap();
            event.putDouble("target", webView.getId());
            // Don't use webView.getUrl() here, the URL isn't updated to the new value yet in callbacks
            // like onPageFinished
            event.putString("url", url);
            event.putBoolean("loading", !mLastLoadFailed && webView.getProgress() != 100);
            event.putString("title", webView.getTitle());
            event.putBoolean("canGoBack", webView.canGoBack());
            event.putBoolean("canGoForward", webView.canGoForward());
            return event;
        }
    }
    
    /**
     * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
     * to call {@link WebView#destroy} on activty destroy event and also to clear the client
     */
    protected static class ReactWebView extends WebView implements LifecycleEventListener {
        private
        @Nullable
        String injectedJS;
        private boolean messagingEnabled = false;
        
        private class ReactWebViewBridge {
            ReactWebView mContext;
            
            ReactWebViewBridge(ReactWebView c) {
                mContext = c;
            }
            
            @JavascriptInterface
            public void postMessage(String message) {
                mContext.onMessage(message);
            }
        }
        
        /**
         * WebView must be created with an context of the current activity
         * <p>
         * Activity Context is required for creation of dialogs internally by WebView
         * Reactive Native needed for access to ReactNative internal system functionality
         */
        public ReactWebView(ThemedReactContext reactContext) {
            super(reactContext);
        }
        
        @Override
        public void onHostResume() {
            // do nothing
        }
        
        @Override
        public void onHostPause() {
            // do nothing
        }
        
        @Override
        public void onHostDestroy() {
            cleanupCallbacksAndDestroy();
        }
        
        public void setInjectedJavaScript(@Nullable String js) {
            injectedJS = js;
        }
        
        public void setMessagingEnabled(boolean enabled) {
            if (messagingEnabled == enabled) {
                return;
            }
            
            messagingEnabled = enabled;
            if (enabled) {
                addJavascriptInterface(new ReactWebViewBridge(this), BRIDGE_NAME);
                linkBridge();
            } else {
                removeJavascriptInterface(BRIDGE_NAME);
            }
        }
        
        public void callInjectedJavaScript() {
            if (getSettings().getJavaScriptEnabled() &&
                injectedJS != null &&
                !TextUtils.isEmpty(injectedJS)) {
                loadUrl("javascript:(function() {\n" + injectedJS + ";\n})();");
            }
        }
        
        public void linkBridge() {
            if (messagingEnabled) {
                if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    // See isNative in lodash
                    String testPostMessageNative = "String(window.postMessage) === String(Object.hasOwnProperty).replace('hasOwnProperty', 'postMessage')";
                    evaluateJavascript(testPostMessageNative, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value.equals("true")) {
                                FLog.w(ReactConstants.TAG, "Setting onMessage on a WebView overrides existing values of window.postMessage, but a previous value was defined");
                            }
                        }
                    });
                }
                
                loadUrl("javascript:(" +
                        "window.originalPostMessage = window.postMessage," +
                        "window.postMessage = function(data) {" +
                        BRIDGE_NAME + ".postMessage(String(data));" +
                        "}" +
                        ")");
            }
        }
        
        public void onMessage(String message) {
            dispatchEvent(this, new TopMessageEvent(this.getId(), message));
        }
        
        private void cleanupCallbacksAndDestroy() {
            setWebViewClient(null);
            destroy();
        }
    }
    
    public ReactWebViewManager() {
        mWebViewConfig = new WebViewConfig() {
            public void configWebView(WebView webView) {
            }
        };
    }
    
    public ReactWebViewManager(WebViewConfig webViewConfig) {
        mWebViewConfig = webViewConfig;
    }
    
    @Override
    public String getName() {
        return REACT_CLASS;
    }
    
    @Override
    protected WebView createViewInstance(final ThemedReactContext reactContext) {
        final ReactWebView webView = new ReactWebView(reactContext);
        
        /**
         * 設置跨域cookie讀取
         * 5.0之前是允許跨域讀取cookie,但在5.0之後就預設false
         * 判斷版本進行設定
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        
        
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                Uri uri = Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                reactContext.getCurrentActivity().startActivity(intent);
                
                //                DownloadManager.Request request = new DownloadManager.Request(
                //                        Uri.parse(url));
                //
                //                request.setMimeType(mimetype);
                //                String cookies = CookieManager.getInstance().getCookie(url);
                //                request.addRequestHeader("cookie", cookies);
                //                request.addRequestHeader("User-Agent", userAgent);
                //                request.allowScanningByMediaScanner();
                ////                request.setTitle()
                //                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); //Notify client once download is completed!
                //                request.setDestinationInExternalPublicDir(
                //                        Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(
                //                                url, contentDisposition, mimetype));
                //                DownloadManager dm = (DownloadManager) reactContext.getCurrentActivity().getSystemService(DOWNLOAD_SERVICE);
                //                dm.enqueue(request);
                //                Toast.makeText(reactContext, "下載中...", //To notify the Client that the file is being downloaded
                //                        Toast.LENGTH_LONG).show();
                
                
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (ReactBuildConfig.DEBUG) {
                    return super.onConsoleMessage(message);
                }
                // Ignore console logs in non debug builds.
                return true;
            }
            
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
            
            private File createImageFile() throws IOException {
                // Create an image file name
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                String imageFileName = "JPEG_" + timeStamp + "_";
                File storageDir = Environment.getExternalStoragePublicDirectory(
                                                                                Environment.DIRECTORY_PICTURES);
                File imageFile = new File(
                                          storageDir,           /* directory */
                                          imageFileName + ".jpg"  /* filename */
                                          );
                return imageFile;
            }
            
            public boolean onShowFileChooser(
                                             WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             WebChromeClient.FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;
                
                Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                if (takePictureIntent.resolveActivity(reactContext.getCurrentActivity().getPackageManager()) != null) {
                    // Create the File where the photo should go
                    File photoFile = null;
                    try {
                        photoFile = createImageFile();
                        takePictureIntent.putExtra("PhotoPath", mCameraPhotoPath);
                    } catch (IOException ex) {
                        // Error occurred while creating the File
                        FLog.e(ReactConstants.TAG, "Unable to create Image File", ex);
                    }
                    
                    // Continue only if the File was successfully created
                    if (photoFile != null) {
                        mCameraPhotoPath = "file:" + photoFile.getAbsolutePath();
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT,
                                                   Uri.fromFile(photoFile));
                    } else {
                        takePictureIntent = null;
                    }
                }
                
                Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentSelectionIntent.setType("*/*");
                
                Intent[] intentArray;
                if (takePictureIntent != null) {
                    intentArray = new Intent[]{takePictureIntent};
                } else {
                    intentArray = new Intent[0];
                }
                
                Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "選擇附件");
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray);
                reactContext.getCurrentActivity().startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE);
                
                // final Intent galleryIntent = new Intent(Intent.ACTION_PICK);
                // galleryIntent.setType("image/*");
                // final Intent chooserIntent = Intent.createChooser(galleryIntent, "Choose File");
                // reactContext.getCurrentActivity().startActivityForResult(chooserIntent, INPUT_FILE_REQUEST_CODE);
                
                
                return true;
            }
            
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                
                if (mVideoView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                
                // Store the view and it's callback for later, so we can dispose of them correctly
                mVideoView = view;
                mCustomViewCallback = callback;
                
                view.setBackgroundColor(Color.BLACK);
                getRootView().addView(view, FULLSCREEN_LAYOUT_PARAMS);
                webView.setVisibility(View.GONE);
                
                UiThreadUtil.runOnUiThread(
                                           new Runnable() {
                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public void run() {
                        // If the status bar is translucent hook into the window insets calculations
                        // and consume all the top insets so no padding will be added under the status bar.
                        View decorView = reactContext.getCurrentActivity().getWindow().getDecorView();
                        decorView.setOnApplyWindowInsetsListener(null);
                        ViewCompat.requestApplyInsets(decorView);
                    }
                });
                
                
                reactContext.getCurrentActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                
                
            }
            
            @Override
            public void onHideCustomView() {
                if (mVideoView == null) {
                    return;
                }
                
                mVideoView.setVisibility(View.GONE);
                getRootView().removeView(mVideoView);
                mVideoView = null;
                mCustomViewCallback.onCustomViewHidden();
                webView.setVisibility(View.VISIBLE);
                //                View decorView = reactContext.getCurrentActivity().getWindow().getDecorView();
                //                // Show Status Bar.
                //                int uiOptions = View.SYSTEM_UI_FLAG_VISIBLE;
                //                decorView.setSystemUiVisibility(uiOptions);
                
                UiThreadUtil.runOnUiThread(
                                           new Runnable() {
                    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
                    @Override
                    public void run() {
                        // If the status bar is translucent hook into the window insets calculations
                        // and consume all the top insets so no padding will be added under the status bar.
                        View decorView = reactContext.getCurrentActivity().getWindow().getDecorView();
                        //                                decorView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                        //                                    @Override
                        //                                    public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                        //                                        WindowInsets defaultInsets = v.onApplyWindowInsets(insets);
                        //                                        return defaultInsets.replaceSystemWindowInsets(
                        //                                                defaultInsets.getSystemWindowInsetLeft(),
                        //                                                0,
                        //                                                defaultInsets.getSystemWindowInsetRight(),
                        //                                                defaultInsets.getSystemWindowInsetBottom());
                        //                                    }
                        //                                });
                        decorView.setOnApplyWindowInsetsListener(null);
                        ViewCompat.requestApplyInsets(decorView);
                    }
                });
                
                
                reactContext.getCurrentActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                
            }
            
            private ViewGroup getRootView() {
                return ((ViewGroup) reactContext.getCurrentActivity().findViewById(android.R.id.content));
            }
            
            
        });
        
        reactContext.addLifecycleEventListener(webView);
        reactContext.addActivityEventListener(new ActivityEventListener() {
            @Override
            public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
                if (requestCode != INPUT_FILE_REQUEST_CODE || mFilePathCallback == null) {
                    return;
                }
                Uri[] results = null;
                
                // Check that the response is a good one
                if (resultCode == Activity.RESULT_OK) {
                    if (data == null) {
                        // If there is not data, then we may have taken a photo
                        if (mCameraPhotoPath != null) {
                            results = new Uri[]{Uri.parse(mCameraPhotoPath)};
                        }
                    } else {
                        String dataString = data.getDataString();
                        if (dataString != null) {
                            results = new Uri[]{Uri.parse(dataString)};
                        }
                    }
                }
                
                if (results == null) {
                    mFilePathCallback.onReceiveValue(new Uri[]{});
                } else {
                    mFilePathCallback.onReceiveValue(results);
                }
                mFilePathCallback = null;
                return;
            }
            
            @Override
            public void onNewIntent(Intent intent) {
            }
        });
        mWebViewConfig.configWebView(webView);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setDefaultFontSize(16);
        webView.getSettings().setTextZoom(100);
        // Fixes broken full-screen modals/galleries due to body height being 0.
        webView.setLayoutParams(
                                new LayoutParams(LayoutParams.MATCH_PARENT,
                                                 LayoutParams.MATCH_PARENT));
        
        if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        
        return webView;
    }
    
    @ReactProp(name = "javaScriptEnabled")
    public void setJavaScriptEnabled(WebView view, boolean enabled) {
        view.getSettings().setJavaScriptEnabled(enabled);
    }
    
    @ReactProp(name = "scalesPageToFit")
    public void setScalesPageToFit(WebView view, boolean enabled) {
        view.getSettings().setUseWideViewPort(!enabled);
    }
    
    @ReactProp(name = "domStorageEnabled")
    public void setDomStorageEnabled(WebView view, boolean enabled) {
        view.getSettings().setDomStorageEnabled(enabled);
    }
    
    @ReactProp(name = "userAgent")
    public void setUserAgent(WebView view, @Nullable String userAgent) {
        if (userAgent != null) {
            // TODO(8496850): Fix incorrect behavior when property is unset (uA == null)
            view.getSettings().setUserAgentString(userAgent);
        }
    }
    
    @ReactProp(name = "mediaPlaybackRequiresUserAction")
    public void setMediaPlaybackRequiresUserAction(WebView view, boolean requires) {
        view.getSettings().setMediaPlaybackRequiresUserGesture(requires);
    }
    
    @ReactProp(name = "allowUniversalAccessFromFileURLs")
    public void setAllowUniversalAccessFromFileURLs(WebView view, boolean allow) {
        view.getSettings().setAllowUniversalAccessFromFileURLs(allow);
    }
    
    @ReactProp(name = "saveFormDataDisabled")
    public void setSaveFormDataDisabled(WebView view, boolean disable) {
        view.getSettings().setSaveFormData(!disable);
    }
    
    @ReactProp(name = "injectedJavaScript")
    public void setInjectedJavaScript(WebView view, @Nullable String injectedJavaScript) {
        ((ReactWebView) view).setInjectedJavaScript(injectedJavaScript);
    }
    
    @ReactProp(name = "messagingEnabled")
    public void setMessagingEnabled(WebView view, boolean enabled) {
        ((ReactWebView) view).setMessagingEnabled(enabled);
    }
    
    @ReactProp(name = "source")
    public void setSource(WebView view, @Nullable ReadableMap source) {
        if (source != null) {
            if (source.hasKey("html")) {
                String html = source.getString("html");
                if (source.hasKey("baseUrl")) {
                    view.loadDataWithBaseURL(
                                             source.getString("baseUrl"), html, HTML_MIME_TYPE, HTML_ENCODING, null);
                } else {
                    view.loadData(html, HTML_MIME_TYPE, HTML_ENCODING);
                }
                return;
            }
            if (source.hasKey("uri")) {
                String url = source.getString("uri");
                String previousUrl = view.getUrl();
                if (previousUrl != null && previousUrl.equals(url)) {
                    return;
                }
                if (source.hasKey("method")) {
                    String method = source.getString("method");
                    if (method.equals(HTTP_METHOD_POST)) {
                        byte[] postData = null;
                        if (source.hasKey("body")) {
                            String body = source.getString("body");
                            try {
                                postData = body.getBytes("UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                postData = body.getBytes();
                            }
                        }
                        if (postData == null) {
                            postData = new byte[0];
                        }
                        view.postUrl(url, postData);
                        return;
                    }
                }
                HashMap<String, String> headerMap = new HashMap<>();
                if (source.hasKey("headers")) {
                    ReadableMap headers = source.getMap("headers");
                    ReadableMapKeySetIterator iter = headers.keySetIterator();
                    while (iter.hasNextKey()) {
                        String key = iter.nextKey();
                        if ("user-agent".equals(key.toLowerCase(Locale.ENGLISH))) {
                            if (view.getSettings() != null) {
                                view.getSettings().setUserAgentString(headers.getString(key));
                            }
                        } else {
                            headerMap.put(key, headers.getString(key));
                        }
                    }
                }
                view.loadUrl(url, headerMap);
                return;
            }
        }
        view.loadUrl(BLANK_URL);
    }
    
    @ReactProp(name = "onContentSizeChange")
    public void setOnContentSizeChange(WebView view, boolean sendContentSizeChangeEvents) {
        if (sendContentSizeChangeEvents) {
            view.setPictureListener(getPictureListener());
        } else {
            view.setPictureListener(null);
        }
    }
    
    @ReactProp(name = "mixedContentMode")
    public void setMixedContentMode(WebView view, @Nullable String mixedContentMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if (mixedContentMode == null || "never".equals(mixedContentMode)) {
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            } else if ("always".equals(mixedContentMode)) {
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            } else if ("compatibility".equals(mixedContentMode)) {
                view.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
            }
        }
    }
    
    @Override
    protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
        // Do not register default touch emitter and let WebView implementation handle touches
        view.setWebViewClient(new ReactWebViewClient());
    }
    
    @Override
    public
    @Nullable
    Map<String, Integer> getCommandsMap() {
        return MapBuilder.of(
                             "goBack", COMMAND_GO_BACK,
                             "goForward", COMMAND_GO_FORWARD,
                             "reload", COMMAND_RELOAD,
                             "stopLoading", COMMAND_STOP_LOADING,
                             "postMessage", COMMAND_POST_MESSAGE,
                             "injectJavaScript", COMMAND_INJECT_JAVASCRIPT
                             );
    }
    
    @Override
    public void receiveCommand(WebView root, int commandId, @Nullable ReadableArray args) {
        switch (commandId) {
            case COMMAND_GO_BACK:
                root.goBack();
                break;
            case COMMAND_GO_FORWARD:
                root.goForward();
                break;
            case COMMAND_RELOAD:
                root.reload();
                break;
            case COMMAND_STOP_LOADING:
                root.stopLoading();
                break;
            case COMMAND_POST_MESSAGE:
                try {
                    JSONObject eventInitDict = new JSONObject();
                    eventInitDict.put("data", args.getString(0));
                    root.loadUrl("javascript:(function () {" +
                                 "var event;" +
                                 "var data = " + eventInitDict.toString() + ";" +
                                 "try {" +
                                 "event = new MessageEvent('message', data);" +
                                 "} catch (e) {" +
                                 "event = document.createEvent('MessageEvent');" +
                                 "event.initMessageEvent('message', true, true, data.data, data.origin, data.lastEventId, data.source);" +
                                 "}" +
                                 "document.dispatchEvent(event);" +
                                 "})();");
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
                break;
            case COMMAND_INJECT_JAVASCRIPT:
                root.loadUrl("javascript:" + args.getString(0));
                break;
        }
    }
    
    @Override
    public void onDropViewInstance(WebView webView) {
        super.onDropViewInstance(webView);
        ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener((ReactWebView) webView);
        ((ReactWebView) webView).cleanupCallbacksAndDestroy();
    }
    
    private WebView.PictureListener getPictureListener() {
        if (mPictureListener == null) {
            mPictureListener = new WebView.PictureListener() {
                @Override
                public void onNewPicture(WebView webView, Picture picture) {
                    dispatchEvent(
                                  webView,
                                  new ContentSizeChangeEvent(
                                                             webView.getId(),
                                                             webView.getWidth(),
                                                             webView.getContentHeight()));
                }
            };
        }
        return mPictureListener;
    }
    
    private static void dispatchEvent(WebView webView, Event event) {
        ReactContext reactContext = (ReactContext) webView.getContext();
        EventDispatcher eventDispatcher =
        reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        eventDispatcher.dispatchEvent(event);
    }
}
