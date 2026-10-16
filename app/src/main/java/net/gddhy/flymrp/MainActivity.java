/*
 * flymrp-android : 网页版 MRP 模拟器 https://flymrp.gddhy.net/ 的安卓 WebView 壳
 * Copyright (C) 2026 gddhy
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version. See the LICENSE file in the project root for details.
 *
 * 上游网页端模拟器: https://github.com/zixing131/flymrp (AGPL-3.0)
 * 本工程不包含上游代码，运行时通过网络加载。
 */
package net.gddhy.flymrp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;

/**
 * flymrp 主界面：全屏 WebView
 * - 正常启动：打开 https://flymrp.gddhy.net/
 * - 以 .mrp 文件启动（VIEW intent）：打开 https://flymrp.gddhy.net/main.html
 *   并把 mrp 文件字节注入页面 #file 输入框、派发 change 事件，
 *   等价于在网页上"选择本地 mrp 文件运行"
 * - flymrp.gddhy.net 域名链接在 WebView 内打开，其他域名跳转系统浏览器
 * - 返回键：分三种情况
 *   1) 正常启动、当前在主页 index.html → 双击退出
 *   2) 正常启动、当前在其他页面 → 走 WebView 历史返回上一级（无历史则回主页）
 *   3) 以 .mrp 文件打开方式启动 → 直接退出 Activity，不回主页
 *   拨号键画布全屏（仅播放页可触发）下，返回键仍转发成右软键(18)给模拟器内部，不受上面影响
 * - mrp 文件打开方式下：网页回到主页（index.html）即视为 mrp 已退出，直接关闭 Activity；
 *   正常从主页进播放页的情况不受影响（回到主页属正常导航）
 */
public class MainActivity extends Activity {

    private static final String TAG = "flymrp";
    private static final String HOME_URL = "https://flymrp.gddhy.net/";
    private static final String PLAYER_URL = "https://flymrp.gddhy.net/main.html";
    private static final String INNER_HOST = "flymrp.gddhy.net";
    private static final long DOUBLE_BACK_INTERVAL = 2000L;
    /** 单次注入的 base64 分片大小，避免 IPC 传输超限 */
    private static final int CHUNK_SIZE = 120000;
    private static final int MAX_INJECT_RETRY = 30;

    /** SAF 请求码 */
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_SAVE_FILE = 1002;
    /** blob 回传的 base64 分片大小，避开 Binder 1MB 限制 */
    private static final int BLOB_CHUNK = 180000;

    /** 重建时保留"是否 mrp 文件打开方式启动 / mrp 是否已跑起来" */
    private static final String STATE_FROM_MRP = "flymrp.from_mrp";
    private static final String STATE_PLAYER_STARTED = "flymrp.player_started";

    /**
     * 拨号键切换"游戏画面全屏"：
     * 1) 隐藏网页标题栏 header.player-bar 与虚拟键盘 #keypad；
     * 2) 把 #stage 固定到整个视口 —— 页面是按 #stage 尺寸自适应算画布的，
     *    改容器而不是改 canvas，不会和页面自己的布局逻辑打架；
     * 3) 缩放不是"适应窗口"时临时改 auto，退出时还原；
     * 4) 补发 resize 催页面重算，画布即铺满。
     */
    private static final String JS_CANVAS_FS =
            "(function(){try{var d=document.documentElement,on=!!window.__fmFsMode;"
                    + "if(!window.__fmFsStyle){var s=document.createElement('style');s.textContent="
                    + "'html.flymrp-fs header.player-bar{display:none!important}'"
                    + "+'html.flymrp-fs #keypad{display:none!important}'"
                    + "+'html.flymrp-fs,html.flymrp-fs body{background:#000!important}'"
                    + "+'html.flymrp-fs #status{display:none!important}'"
                    // 页面算缩放时写死了预留量：宽度预留 32px、高度预留 26px，
                    //   d = min((stage.clientWidth - 32)/s, (stage.clientHeight - 26)/r)
                    // 把 #stage 撑到比视口大 32x26 并各反向偏移一半，正好抵消这段预留量，
                    // 画布即可贴到屏幕边缘（某一方向贴边后停止缩放，另一方向留黑边）
                    + "+'html.flymrp-fs #stage{position:fixed!important;left:-16px!important;top:-13px!important;'"
                    + "+'width:calc(100% + 32px)!important;height:calc(100% + 26px)!important;'"
                    + "+'padding:0!important;z-index:2147483646!important;background:#000!important}"
                    + "';(document.head||d).appendChild(s);window.__fmFsStyle=1;}"
                    + "function poke(){try{window.dispatchEvent(new Event('resize'));}catch(e){}}"
                    + "function setZoom(v){try{var z=document.getElementById('zoom');"
                    + "if(z&&z.value!==v){z.value=v;z.dispatchEvent(new Event('change',{bubbles:true}));}}catch(e){}}"
                    + "if(on){d.classList.remove('flymrp-fs');window.__fmFsMode=0;"
                    + "if(window.__fmPrevZoom){setZoom(window.__fmPrevZoom);window.__fmPrevZoom=null;}"
                    + "poke();setTimeout(poke,80);return 'exit';}"
                    + "d.classList.add('flymrp-fs');window.__fmFsMode=1;"
                    + "try{var z=document.getElementById('zoom');if(z){window.__fmPrevZoom=z.value;}}catch(e){}"
                    + "setZoom('auto');"
                    + "poke();setTimeout(poke,80);setTimeout(poke,250);"
                    + "return 'enter';}catch(e){return 'err:'+e;}})()";

    /**
     * 拨号键全屏模式下，把菜单键/返回键映射成模拟器的左右软键。
     * 页面按键表 Ps：SoftLeft -> SOFTLEFT(17)、SoftRight -> SOFTRIGHT(18)，
     * 监听在 window 的 keydown / keyup 上，按下与抬起都要派发。
     */
    private String buildKeyJs(String code, boolean down, int mrpCode) {
        return "(function(){try{var code='" + code + "',down=" + (down ? "true" : "false") + ";"
                + "var ev=new KeyboardEvent(down?'keydown':'keyup',"
                + "{bubbles:true,cancelable:true,key:code,code:code});"
                + "try{Object.defineProperty(ev,'keyCode',{get:function(){return " + mrpCode + ";}});}catch(e){}"
                + "try{Object.defineProperty(ev,'which',{get:function(){return " + mrpCode + ";}});}catch(e){}"
                + "window.dispatchEvent(ev);return 'ok';}catch(e){return 'err:'+e;}})()";
    }

    /**
     * 拦截页面里 <a download>（blob: 链接）的点击 —— 网页截图就是：
     * canvas.toBlob -> URL.createObjectURL -> <a download>.click()
     * 这里把 blob 转成分片 base64 回传，交给 App 用 SAF 保存到用户指定位置。
     */
    private static final String JS_BRIDGE =
            "(function(){try{var h=window.FlyMrpHost;if(!h){return 'no-bridge';}"
                    + "if(window.__fmBridgeInit){return 'ok';}"
                    + "var orig=HTMLAnchorElement.prototype.click;"
                    + "HTMLAnchorElement.prototype.click=function(){"
                    + "try{if(this.download&&this.href&&this.href.indexOf('blob:')===0){"
                    + "var a=this;fetch(a.href).then(function(r){return r.blob();}).then(function(b){"
                    + "var fr=new FileReader();"
                    + "fr.onloadend=function(){var res=fr.result||'';var i=res.indexOf(',');"
                    + "var b64=i>=0?res.substring(i+1):'';"
                    + "var CH=" + BLOB_CHUNK + ",n=Math.max(1,Math.ceil(b64.length/CH));"
                    + "for(var k=0;k<n;k++){h.onBlobPart(a.download,b.type||'application/octet-stream',k,n,"
                    + "b64.substring(k*CH,(k+1)*CH));}};"
                    + "fr.readAsDataURL(b);}).catch(function(e){h.onBlobError(String(e));});"
                    + "return;}}catch(e){}"
                    + "return orig.apply(this,arguments);};"
                    + "window.__fmBridgeInit=1;return 'ok';}catch(e){return 'err:'+e;}})()";

    /**
     * 网页侧的加载逻辑：
     *   It = document.querySelector("#file")
     *   It.addEventListener("change", () => { const e = It.files?.[0]; It.value=""; e && kt(e.name, () => e.arrayBuffer()); })
     * 这里构造 File 塞进 #file 并派发 change，走的是页面自己的处理流程。
     */
    private static final String JS_RUN =
            "(function(){try{"
                    + "var n=window.__fmn||'game.mrp';"
                    + "var b=window.__fm||'';"
                    + "if(!b){return 'empty';}"
                    + "var s=atob(b),l=s.length,u=new Uint8Array(l);"
                    + "for(var i=0;i<l;i++){u[i]=s.charCodeAt(i);}"
                    + "var f;try{f=new File([u],n,{type:'application/octet-stream'});}"
                    + "catch(e){f=new Blob([u],{type:'application/octet-stream'});}"
                    + "try{Object.defineProperty(f,'name',{value:n,configurable:true});}catch(e){}"
                    + "try{Object.defineProperty(f,'arrayBuffer',{value:function(){return Promise.resolve(u.buffer);},configurable:true});}catch(e){}"
                    + "var el=document.getElementById('file');"
                    + "if(!el){return 'no-input';}"
                    + "var fs=null;"
                    + "if(typeof DataTransfer==='function'){try{var dt=new DataTransfer();dt.items.add(f);fs=dt.files;}catch(e){}}"
                    + "if(!fs){fs={0:f,length:1,item:function(i){return i===0?f:null;}};}"
                    + "try{el.files=fs;}"
                    + "catch(e){try{Object.defineProperty(el,'files',{value:fs,configurable:true});}catch(e2){return 'no-files';}}"
                    + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "window.__fm=null;"
                    + "return 'ok';"
                    + "}catch(e){return 'err:'+e;}})()";

    /**
     * 读取网页主题与"栏"的实际颜色：
     *   主题：document.documentElement.dataset.theme = "dark" | "light"
     *   栏色：顶部 .player-bar、底部 .controller 均使用 var(--panel)，直接用它们计算后的背景色，
     *         保证系统状态栏/导航栏与网页标题栏严丝合缝（深色 #1c1c1e / 浅色 #ffffff）
     * 并用 MutationObserver 监听 data-theme 变化，网页切主题时同步换肤。
     */
    private static final String JS_THEME =
            "(function(){try{var h=window.FlyMrpHost;if(!h){return 'no-bridge';}"
                    + "function cur(){var t='';"
                    + "try{t=document.documentElement.dataset.theme||'';}catch(e){}"
                    + "if(!t){try{t=localStorage.getItem('flymrp.theme')||'';}catch(e){}}"
                    + "if(!t&&window.matchMedia){try{t=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}catch(e){}}"
                    + "return t==='dark'?'dark':'light';}"
                    + "function cbg(el){if(!el)return '';var s='';"
                    + "try{s=getComputedStyle(el).backgroundColor||'';}catch(e){return '';}"
                    + "if(!s||s==='transparent'||s.indexOf('rgba(0, 0, 0, 0)')===0)return '';return s;}"
                    + "function chrome(){"
                    + "var body=cbg(document.body);"
                    + "var top=cbg(document.querySelector('.player-bar'))||cbg(document.querySelector('header'))||body;"
                    + "var bot=cbg(document.querySelector('.controller'))||cbg(document.querySelector('footer'))||body;"
                    + "return top+'|'+bot+'|'+body;}"
                    + "function report(){h.onTheme(cur());var c=chrome().split('|');"
                    + "h.onChrome(c[0],c[1],c[2]);}"
                    + "report();"
                    + "if(window.__fmThemeInit){return 'ok';}"
                    + "if(typeof MutationObserver==='function'){"
                    + "var mo=new MutationObserver(report);"
                    + "mo.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});}"
                    + "try{matchMedia('(prefers-color-scheme: dark)').addEventListener('change',report);}catch(e){}"
                    + "window.__fmThemeInit=1;return 'ok';}catch(e){return 'err:'+e;}})()";

    /**
     * mrp 文件打开方式启动时的"退出监听"。
     * 线上播放页 main.html 在 mrp 退出（或点页面里的"返回"）后走 player.js 的 pt()：
     *   location.assign(https://flymrp.gddhy.net/index.html)   // 普通浏览器/非 KaiOS
     *   location.replace(同上的 index.html)                     // KaiOS 且 referrer 不是 index.html
     *   history.back()                                          // referrer 是 index.html 时
     * 前两种是真实页面跳转，WebViewClient 里就能拦住；history.back()（以及将来若改
     * 前端路由 pushState/replaceState）不会走 shouldOverrideUrlLoading，这里在页面内兜一层：
     * 一旦路径既不是 main.html、又落在主页（/ 或 index.html），就回调宿主退出 App。
     */
    private static final String JS_EXIT_WATCH =
            "(function(){try{var h=window.FlyMrpHost;if(!h){return 'no-bridge';}"
                    + "if(window.__fmExitWatch){return 'ok';}"
                    + "window.__fmExitWatch=1;"
                    + "function path(){var p='';try{p=String(location.pathname||'');}catch(e){p='';}return p;}"
                    + "function isPlayer(){return path().indexOf('main.html')>=0;}"
                    + "function isHome(){var p=path();"
                    + "if(!p||p==='/'||p.charAt(p.length-1)==='/'){return true;}"
                    + "return p.indexOf('index.html')>=0;}"
                    + "function chk(){if(isPlayer()||!isHome()){return false;}"
                    + "if(window.__fmExitSent){return true;}window.__fmExitSent=1;"
                    + "try{h.onPlayerExit(String(location.href||''));}catch(e){}return true;}"
                    + "window.addEventListener('popstate',chk);"
                    + "try{var ps=history.pushState,rs=history.replaceState;"
                    + "history.pushState=function(){var r=ps.apply(this,arguments);chk();return r;};"
                    + "history.replaceState=function(){var r=rs.apply(this,arguments);chk();return r;};"
                    + "}catch(e){}"
                    + "setInterval(chk,500);"
                    + "return 'ok';}catch(e){return 'err:'+e;}})()";

    private static final String PREF_NAME = "flymrp";
    private static final String PREF_DARK = "theme_dark";
    private static final String PREF_TOP = "color_top";
    private static final String PREF_BOTTOM = "color_bottom";
    private static final String PREF_BG = "color_bg";

    private WebView mWebView;
    private FrameLayout mRoot;
    private ProgressBar mSpinner;
    private ProgressBar mProgressBar;
    private long mLastBackTime = 0L;
    private boolean mDarkTheme = false;
    private int mChromeTop = 0;
    private int mChromeBottom = 0;
    private int mPageBg = 0;
    private SharedPreferences mPrefs;
    private final ThemeBridge mThemeBridge = new ThemeBridge(this);

    /** 网页 <input type="file"> 的回调 */
    private ValueCallback<Uri[]> mFilePathCallback;
    /** 待写入 SAF 的数据 */
    private byte[] mPendingSave;
    /** blob 分片拼接缓冲 */
    private StringBuilder mBlobBuilder;

    /** 网页全屏相关 */
    private View mCustomView;
    private FrameLayout mFullscreenContainer;
    private WebChromeClient.CustomViewCallback mCustomViewCallback;
    private int mOriginalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private int mOriginalUiFlags = 0;

    /** 拨号键触发的画布全屏状态 */
    private boolean mCanvasFullscreen = false;
    private int mCanvasUiFlags = 0;

    private byte[] mPendingBytes;
    private String mPendingName;
    private boolean mInjectPending = false;
    private int mInjectRetry = 0;

    /** 是否由 .mrp 文件关联启动（此模式下 mrp 退出后直接关掉 Activity） */
    private boolean mFromMrp = false;
    /** mrp 是否已成功送入网页并开始运行（避免页面早期跳转被误判成"mrp 已退出"） */
    private boolean mPlayerStarted = false;
    /** 退出流程是否已触发，防止重复 finish */
    private boolean mExiting = false;

    private final Runnable mInjectRunnable = new Runnable() {
        @Override
        public void run() {
            injectMrpFile();
        }
    };

    private final ValueCallback<String> mInjectCallback = new ValueCallback<String>() {
        @Override
        public void onReceiveValue(String value) {
            Log.d(TAG, "inject result: " + value);
            if (value != null && value.contains("no-input") && mInjectRetry < MAX_INJECT_RETRY) {
                // 页面尚未渲染出 #file 输入框，稍后重试
                mInjectRetry++;
                mWebView.postDelayed(mInjectRunnable, 300L);
                return;
            }
            if (value == null
                    || value.contains("err:")
                    || value.contains("no-files")
                    || value.contains("empty")) {
                Toast.makeText(MainActivity.this, R.string.mrp_load_failed, Toast.LENGTH_LONG).show();
                return;
            }
            // mrp 已真正跑起来了：此后网页一旦回到主页，就说明 mrp 退出了
            mPlayerStarted = true;
            mWebView.evaluateJavascript(JS_EXIT_WATCH, null);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 启用硬件加速（Manifest 已开启，代码里再显式加固一层）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        // 先按上次记录的 / 系统的深浅色设置窗口底色，避免启动瞬间的白/黑闪屏
        mPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        mDarkTheme = mPrefs.getBoolean(PREF_DARK, isSystemDark());
        // 还原上次从网页取到的配色，冷启动即可按正确主题上色
        mChromeTop = mPrefs.getInt(PREF_TOP, 0);
        mChromeBottom = mPrefs.getInt(PREF_BOTTOM, 0);
        mPageBg = mPrefs.getInt(PREF_BG, 0);
        applyWindowTheme(mDarkTheme);

        setContentView(R.layout.activity_main);

        mWebView = (WebView) findViewById(R.id.webview);
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mRoot = (FrameLayout) findViewById(R.id.root);
        mSpinner = (ProgressBar) findViewById(R.id.spinner);
        mProgressBar = (ProgressBar) findViewById(R.id.progress);
        applyViewTheme(mDarkTheme);
        mWebView.addJavascriptInterface(mThemeBridge, "FlyMrpHost");

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        // 允许读取 SAF 返回的 content:// （网页选择文件上传时需要）
        settings.setAllowContentAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        // 允许 https 页面加载 http 子资源（MRP 模拟器页面可能引用 http 资源）
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (shouldExitToHome(url)) {
                    exitApp();
                    return true;
                }
                return handleUrl(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (shouldExitToHome(url)) {
                    exitApp();
                    return true;
                }
                return handleUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // 保险：mrp 文件打开方式下，页面已经开始往主页跳了就直接收摊
                if (shouldExitToHome(url)) {
                    view.stopLoading();
                    exitApp();
                    return;
                }
                // 页面切换时画布全屏状态失效，恢复系统栏
                resetCanvasFullscreen();
                mProgressBar.setVisibility(View.VISIBLE);
                mProgressBar.setProgress(0);
                mSpinner.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mSpinner.setVisibility(View.GONE);
                mProgressBar.setProgress(100);
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        mProgressBar.setVisibility(View.GONE);
                    }
                }, 200L);
                // 页面就绪后读取网页主题并对 App 换肤
                view.evaluateJavascript(JS_THEME, null);
                // 挂载 blob 下载拦截
                view.evaluateJavascript(JS_BRIDGE, null);
                if (mInjectPending && url != null && url.startsWith(PLAYER_URL)) {
                    mInjectRetry = 0;
                    // 给 SPA 一点时间渲染出 #file 输入框
                    view.postDelayed(mInjectRunnable, 400L);
                }
                // mrp 文件打开方式：在播放页挂上"回到主页即退出"的监听
                if (mFromMrp && mPlayerStarted && url != null && url.startsWith(PLAYER_URL)) {
                    view.evaluateJavascript(JS_EXIT_WATCH, null);
                }
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgressBar.setProgress(newProgress);
                mProgressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                // 无 ActionBar，标题仅用于无障碍/调试
            }

            /** 网页发起全屏（Fullscreen API / video）：铺满全屏、隐藏系统栏、锁定当前方向 */
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                enterFullscreen(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            /** 网页 <input type="file"> -> 系统文件选择器（SAF，无需存储权限） */
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (mFilePathCallback != null) {
                    mFilePathCallback.onReceiveValue(null);
                }
                mFilePathCallback = filePathCallback;

                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(resolveMimeType(fileChooserParams.getAcceptTypes()));
                String[] extra = resolveExtraMimeTypes(fileChooserParams.getAcceptTypes());
                if (extra.length > 0) {
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, extra);
                }
                if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(
                            Intent.createChooser(intent, getString(R.string.choose_file)),
                            REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    mFilePathCallback.onReceiveValue(null);
                    mFilePathCallback = null;
                    Toast.makeText(MainActivity.this, R.string.no_file_manager, Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        mWebView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                String name = guessFileName(url, contentDisposition, mimetype);
                if (url == null) {
                    return;
                }
                if (url.startsWith("blob:")) {
                    // 未被 JS 拦截到的 blob：走 JS 取数据再存
                    mWebView.evaluateJavascript(buildFetchBlobJs(url, name, mimetype), null);
                } else if (url.startsWith("http://") || url.startsWith("https://")) {
                    downloadViaHttp(url, name);
                } else {
                    Toast.makeText(MainActivity.this, R.string.cannot_download, Toast.LENGTH_SHORT).show();
                }
            }
        });

        if (savedInstanceState != null) {
            mFromMrp = savedInstanceState.getBoolean(STATE_FROM_MRP, false);
            mPlayerStarted = savedInstanceState.getBoolean(STATE_PLAYER_STARTED, false);
            mWebView.restoreState(savedInstanceState);
        } else {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /* ---------------- 网页全屏：铺满、隐藏系统栏、锁定当前方向 ---------------- */

    private boolean isFullscreen() {
        return mCustomView != null;
    }

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        mCustomView = view;
        mCustomViewCallback = callback;
        mOriginalOrientation = getRequestedOrientation();
        mOriginalUiFlags = getWindow().getDecorView().getSystemUiVisibility();

        // 锁定当前方向：进入全屏后不随传感器旋转
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        mFullscreenContainer = new FrameLayout(this);
        mFullscreenContainer.setBackgroundColor(Color.BLACK);
        mFullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        mRoot.addView(mFullscreenContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        mWebView.setVisibility(View.GONE);
        mSpinner.setVisibility(View.GONE);
        mProgressBar.setVisibility(View.GONE);
        applyFullscreenFlags();
    }

    private void exitFullscreen() {
        if (mCustomView == null) {
            return;
        }
        if (mFullscreenContainer != null) {
            mRoot.removeView(mFullscreenContainer);
            mFullscreenContainer = null;
        }
        mCustomView = null;
        mWebView.setVisibility(View.VISIBLE);
        if (mCustomViewCallback != null) {
            mCustomViewCallback.onCustomViewHidden();
            mCustomViewCallback = null;
        }
        setRequestedOrientation(mOriginalOrientation);
        getWindow().getDecorView().setSystemUiVisibility(mOriginalUiFlags);
        // 恢复状态栏/导航栏（颜色、图标明暗按当前主题还原）
        applyWindowTheme(mDarkTheme);
    }

    private void applyFullscreenFlags() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /**
     * 按键处理：
     * - 拨号键（KEYCODE_CALL）：keydown/keyup 都直接消费掉，避免拉起系统拨号；
     *   main.html 下用 keydown 切换游戏画面全屏
     * - 拨号键触发的全屏模式下：菜单键 -> 左软键(17)，返回键 -> 右软键(18)，
     *   按下与抬起都转发给页面模拟器并消费掉事件
     * - 其余情况（非拨号键全屏、其它按键）行为不变
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_CALL) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (isPlayerPage()) {
                    toggleCanvasFullscreen();
                }
            }
            return true; // 按下与抬起都消费
        }
        if (mCanvasFullscreen
                && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK)) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            // 菜单键 -> 左软键；返回键 -> 右软键
            String webCode = keyCode == KeyEvent.KEYCODE_MENU ? "SoftLeft" : "SoftRight";
            int mrpCode = keyCode == KeyEvent.KEYCODE_MENU ? 17 : 18;
            if (mWebView != null) {
                mWebView.evaluateJavascript(buildKeyJs(webCode, down, mrpCode), null);
            }
            return true; // 全屏模式下这两个键不再走系统行为
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isPlayerPage() {
        String url = mWebView == null ? null : mWebView.getUrl();
        return url != null && url.startsWith(PLAYER_URL);
    }

    /* -------- mrp 文件打开方式：mrp 退出（网页回主页）即关闭 Activity -------- */

    /** 由 .mrp 文件关联启动、且 mrp 已经真的跑起来了 */
    private boolean inMrpSession() {
        return mFromMrp && mPlayerStarted && !mExiting;
    }

    /** 该 URL 是否站点主页（index.html 或站点根目录） */
    private static boolean isHomeUrl(String url) {
        if (!isInternalUrl(url)) {
            return false;
        }
        String path = Uri.parse(url).getPath();
        if (TextUtils.isEmpty(path) || "/".equals(path) || path.endsWith("/")) {
            return true;
        }
        return path.toLowerCase(Locale.US).endsWith("index.html");
    }

    /**
     * 是否应当"因为 mrp 退出而关掉 Activity"：
     * 仅当本次是 .mrp 文件打开方式启动、mrp 已经运行起来、且网页正往主页（index.html）跳。
     * 从主页进播放页再回主页的正常流程不受影响。
     */
    private boolean shouldExitToHome(String url) {
        return inMrpSession() && isHomeUrl(url);
    }

    /** 关了 Activity 就等于退出模拟器，直接回文件管理器 */
    private void exitApp() {
        if (mExiting) {
            return;
        }
        mExiting = true;
        Log.d(TAG, "mrp exited, finish activity");
        finish();
    }

    /** 网页侧兜底回调：页内已切回主页（history.back / 前端路由）时也照样退出 */
    private void onPlayerExitFromWeb(String url) {
        if (mExiting || !mFromMrp || !mPlayerStarted) {
            return;
        }
        if (url != null && url.startsWith(PLAYER_URL)) {
            return;
        }
        exitApp();
    }

    /* ---------------------------- 返回键 ---------------------------- */

    /**
     * 返回键（拨号键画布全屏时不会走到这里，已在 dispatchKeyEvent 里转成右软键给模拟器）：
     * 1) mrp 文件打开方式启动：直接退出 Activity（不回主页，也不给模拟器内部）；
     * 2) 正常启动且当前在主页 index.html：双击退出；
     * 3) 正常启动且当前在其他页面：走 WebView 历史返回上一级，没有历史可退就回主页。
     */
    private boolean handleBackKey() {
        if (mFromMrp) {
            exitApp();
            return true;
        }
        String url = mWebView == null ? null : mWebView.getUrl();
        if (!isHomeUrl(url)) {
            if (mWebView.canGoBack()) {
                mWebView.goBack();
            } else {
                // 没有历史可退（正常流程少见）：当作"回到上一级"回主页
                mWebView.loadUrl(HOME_URL);
            }
            return true;
        }
        // 主页：双击返回键退出
        long now = System.currentTimeMillis();
        if (now - mLastBackTime < DOUBLE_BACK_INTERVAL) {
            finish();
        } else {
            mLastBackTime = now;
            Toast.makeText(this, R.string.press_again_to_exit, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void toggleCanvasFullscreen() {
        mCanvasFullscreen = !mCanvasFullscreen;
        if (mWebView != null) {
            mWebView.evaluateJavascript(JS_CANVAS_FS, null);
        }
        if (mCanvasFullscreen) {
            mCanvasUiFlags = getWindow().getDecorView().getSystemUiVisibility();
            applyFullscreenFlags();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(mCanvasUiFlags);
            applyWindowTheme(mDarkTheme);
        }
    }

    private void resetCanvasFullscreen() {
        if (!mCanvasFullscreen) {
            return;
        }
        mCanvasFullscreen = false;
        getWindow().getDecorView().setSystemUiVisibility(mCanvasUiFlags);
        applyWindowTheme(mDarkTheme);
    }

    /* ---------------- 文件选择 / 下载：全部走 SAF，不申请存储权限 ---------------- */

    /** accept 里可能是 ".mrp" 这种非法 mime，需要过滤 */
    private static String resolveMimeType(String[] acceptTypes) {
        if (acceptTypes != null) {
            for (String type : acceptTypes) {
                if (type != null && type.contains("/")) {
                    return type;
                }
            }
        }
        return "*/*";
    }

    private static String[] resolveExtraMimeTypes(String[] acceptTypes) {
        ArrayList<String> list = new ArrayList<String>();
        if (acceptTypes != null) {
            for (String type : acceptTypes) {
                if (type != null && type.contains("/") && type.indexOf('*') < 0) {
                    list.add(type);
                }
            }
        }
        return list.toArray(new String[0]);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                ClipData clip = data.getClipData();
                if (clip != null && clip.getItemCount() > 0) {
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            if (mFilePathCallback != null) {
                mFilePathCallback.onReceiveValue(results);
                mFilePathCallback = null;
            }
            return;
        }
        if (requestCode == REQ_SAVE_FILE) {
            byte[] bytes = mPendingSave;
            mPendingSave = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                writeToUri(data.getData(), bytes);
            }
        }
    }

    /** 用 SAF 让用户选择保存位置后写入 */
    private void saveViaSaf(byte[] bytes, String fileName, String mimeType) {
        if (bytes == null || bytes.length == 0) {
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        mPendingSave = bytes;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(TextUtils.isEmpty(mimeType) ? "application/octet-stream" : mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_SAVE_FILE);
        } catch (ActivityNotFoundException e) {
            mPendingSave = null;
            Toast.makeText(this, R.string.no_file_manager, Toast.LENGTH_SHORT).show();
        }
    }

    private void writeToUri(final Uri uri, final byte[] bytes) {
        if (uri == null || bytes == null) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                OutputStream os = null;
                try {
                    os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.flush();
                        ok = true;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "write saf uri failed", e);
                } finally {
                    closeQuietly(os);
                }
                final boolean result = ok;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,
                                result ? R.string.file_saved : R.string.save_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** 普通 http(s) 下载：先用网络取回字节，再交给 SAF 保存 */
    private void downloadViaHttp(final String url, final String fileName) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent", mWebView.getSettings().getUserAgentString());
                    conn.connect();
                    final String mime = conn.getContentType();
                    in = conn.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    final byte[] bytes = bos.toByteArray();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            saveViaSaf(bytes, fileName, mime);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "download failed", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, R.string.save_failed, Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    closeQuietly(in);
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    /** blob: 下载兜底：让 JS 取回数据（拦截没生效时） */
    private String buildFetchBlobJs(String url, String name, String mime) {
        String safeMime = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        return "(function(){try{var h=window.FlyMrpHost;if(!h){return 'no-bridge';}"
                + "fetch('" + jsString(url) + "').then(function(r){return r.blob();}).then(function(b){"
                + "var fr=new FileReader();"
                + "fr.onloadend=function(){var res=fr.result||'';var i=res.indexOf(',');"
                + "var b64=i>=0?res.substring(i+1):'';"
                + "var CH=" + BLOB_CHUNK + ",n=Math.max(1,Math.ceil(b64.length/CH));"
                + "for(var k=0;k<n;k++){h.onBlobPart('" + jsString(name) + "','" + jsString(safeMime)
                + "',k,n,b64.substring(k*CH,(k+1)*CH));}};"
                + "fr.readAsDataURL(b);}).catch(function(e){h.onBlobError(String(e));});"
                + "return 'ok';}catch(e){return 'err:'+e;}})()";
    }

    /** 收集 JS 回传的 blob 分片，收满后解码并交给 SAF */
    private void onBlobPart(String name, String mime, int index, int total, String data) {
        if (index == 0 || mBlobBuilder == null) {
            mBlobBuilder = new StringBuilder();
        }
        mBlobBuilder.append(data == null ? "" : data);
        if (index >= total - 1) {
            String b64 = mBlobBuilder.toString();
            mBlobBuilder = null;
            byte[] bytes = null;
            try {
                bytes = Base64.decode(b64, Base64.DEFAULT);
            } catch (Exception e) {
                Log.w(TAG, "decode blob failed", e);
            }
            saveViaSaf(bytes, name, mime);
        }
    }

    private void onBlobError(String message) {
        Log.w(TAG, "blob fetch failed: " + message);
        Toast.makeText(this, R.string.save_failed, Toast.LENGTH_SHORT).show();
    }

    private static String guessFileName(String url, String contentDisposition, String mimeType) {
        String name = null;
        if (!TextUtils.isEmpty(contentDisposition)) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("filename\\*?=['\"]?(?:UTF-8'')?([^;'\"\\n\\r]*)['\"]?")
                        .matcher(contentDisposition);
                if (m.find()) {
                    name = m.group(1);
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        if (TextUtils.isEmpty(name) && url != null) {
            int cut = url.lastIndexOf('/');
            if (cut >= 0 && cut < url.length() - 1) {
                name = url.substring(cut + 1);
            }
            int q = name == null ? -1 : name.indexOf('?');
            if (q > 0) {
                name = name.substring(0, q);
            }
        }
        if (TextUtils.isEmpty(name) || name.indexOf("blob:") >= 0) {
            String ext = "";
            if (!TextUtils.isEmpty(mimeType)) {
                String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
                if (!TextUtils.isEmpty(e)) {
                    ext = "." + e;
                }
            }
            name = "download-" + System.currentTimeMillis() + ext;
        }
        return name;
    }

    /** 处理启动 intent：带 .mrp 文件则打开播放器页并注入，否则打开首页 */
    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                byte[] bytes = readUri(uri);
                if (bytes != null && bytes.length > 0) {
                    mPendingBytes = bytes;
                    mPendingName = resolveName(uri);
                    mInjectPending = true;
                    mInjectRetry = 0;
                    // 文件关联启动：mrp 退出后直接关掉 Activity
                    mFromMrp = true;
                    mPlayerStarted = false;
                    mExiting = false;
                    mWebView.loadUrl(PLAYER_URL);
                    return;
                }
                Toast.makeText(this, R.string.mrp_read_failed, Toast.LENGTH_LONG).show();
            }
        }
        // 正常启动/主页入口：主页 <-> 播放页之间来回跳属正常导航，不退出 App
        mFromMrp = false;
        mPlayerStarted = false;
        mWebView.loadUrl(HOME_URL);
    }

    /** 读取 intent 携带的文件（content:// 走临时授权，无需存储权限） */
    private byte[] readUri(Uri uri) {
        InputStream in = null;
        try {
            String scheme = uri.getScheme();
            if ("content".equals(scheme)) {
                in = getContentResolver().openInputStream(uri);
            } else if ("file".equals(scheme)) {
                in = new FileInputStream(uri.getPath());
            } else {
                return null;
            }
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "read uri failed", e);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 取得文件名；content:// 从 DISPLAY_NAME 查，file:// 取路径末段 */
    private String resolveName(Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        name = cursor.getString(idx);
                    }
                }
            } catch (Exception ignored) {
                // 某些 ContentProvider 不支持查询，回退到路径末段
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (name == null || name.length() == 0) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.length() == 0) {
            name = "game.mrp";
        }
        if (!name.toLowerCase(Locale.US).endsWith(".mrp")) {
            name = name + ".mrp";
        }
        return name;
    }

    /** 把 mrp 文件以 base64 分片写入页面，再构造 File 触发 change 事件 */
    private void injectMrpFile() {
        if (mPendingBytes == null || mWebView == null) {
            return;
        }
        mInjectPending = false;
        String b64 = Base64.encodeToString(mPendingBytes, Base64.NO_WRAP);
        mWebView.evaluateJavascript("window.__fmn='" + jsString(mPendingName) + "';", null);
        mWebView.evaluateJavascript("window.__fm='';", null);
        for (int i = 0; i < b64.length(); i += CHUNK_SIZE) {
            int end = Math.min(b64.length(), i + CHUNK_SIZE);
            mWebView.evaluateJavascript("window.__fm+='" + b64.substring(i, end) + "';", null);
        }
        mWebView.evaluateJavascript(JS_RUN, mInjectCallback);
    }

    /** JS 字符串字面量转义（含非 ASCII 转 U+XXXX 转义） */
    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\'' || c == '"') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c < 0x20 || c > 0x7e) {
                sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static void closeQuietly(OutputStream out) {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    /** 网页主题回调桥：JS -> Java（回调发生在 WebView 线程，需切回主线程） */
    public static final class ThemeBridge {
        private final WeakReference<MainActivity> mRef;

        ThemeBridge(MainActivity activity) {
            mRef = new WeakReference<MainActivity>(activity);
        }

        @JavascriptInterface
        public void onTheme(final String theme) {
            final MainActivity activity = mRef.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.applyTheme("dark".equals(theme));
                }
            });
        }

        /** 网页顶栏 / 底栏 / 页面底色的实际颜色（rgb() 或 rgba() 字符串） */
        @JavascriptInterface
        public void onChrome(final String topColor, final String bottomColor, final String bgColor) {
            final MainActivity activity = mRef.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.applyChrome(topColor, bottomColor, bgColor);
                }
            });
        }

        /** blob 下载：分片回传 base64 */
        @JavascriptInterface
        public void onBlobPart(final String name, final String mime, final int index,
                               final int total, final String data) {
            final MainActivity activity = mRef.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.onBlobPart(name, mime, index, total, data);
                }
            });
        }

        @JavascriptInterface
        public void onBlobError(final String message) {
            final MainActivity activity = mRef.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.onBlobError(message);
                }
            });
        }

        /** 页内检测到已离开播放页、回到主页（mrp 退出） */
        @JavascriptInterface
        public void onPlayerExit(final String url) {
            final MainActivity activity = mRef.get();
            if (activity == null) {
                return;
            }
            activity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    activity.onPlayerExitFromWeb(url);
                }
            });
        }
    }

    /** 系统是否处于深色模式（页面未给出主题时的默认值） */
    private boolean isSystemDark() {
        int mask = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mask == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 换肤入口：记录到偏好，同步窗口色与加载动画色 */
    private void applyTheme(boolean dark) {
        mDarkTheme = dark;
        mPrefs.edit().putBoolean(PREF_DARK, dark).apply();
        applyWindowTheme(dark);
        applyViewTheme(dark);
    }

    /** 记录网页顶栏/底栏/底色并持久化，下次冷启动直接套用（不再先白一下） */
    private void applyChrome(String topCss, String bottomCss, String bgCss) {
        int panel = getColorValue(mDarkTheme ? R.color.dark_panel : R.color.light_panel);
        int themeBg = getColorValue(mDarkTheme ? R.color.dark_bg : R.color.light_bg);
        mChromeTop = parseCssColor(topCss, panel);
        mChromeBottom = parseCssColor(bottomCss, panel);
        mPageBg = parseCssColor(bgCss, themeBg);
        mPrefs.edit()
                .putInt(PREF_TOP, mChromeTop)
                .putInt(PREF_BOTTOM, mChromeBottom)
                .putInt(PREF_BG, mPageBg)
                .apply();
        applyWindowTheme(mDarkTheme);
        applyViewTheme(mDarkTheme);
    }

    /** 状态栏对齐网页顶栏、导航栏对齐网页底栏；窗口底色用网页底色防闪屏 */
    private void applyWindowTheme(boolean dark) {
        int bg = mPageBg != 0 ? mPageBg : getColorValue(dark ? R.color.dark_bg : R.color.light_bg);
        int panel = getColorValue(dark ? R.color.dark_panel : R.color.light_panel);
        int top = mChromeTop != 0 ? mChromeTop : panel;
        int bottom = mChromeBottom != 0 ? mChromeBottom : panel;

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(bg));
        window.setStatusBarColor(top);
        window.setNavigationBarColor(bottom);

        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        // 图标颜色按各自栏底的明暗决定，避免"深色栏 + 深色图标"
        if (isLightColor(top)) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (isLightColor(bottom)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

    /** 解析 CSS 的 rgb()/rgba()；半透明时与主题底色混合成不透明色 */
    private int parseCssColor(String css, int fallback) {
        if (css == null) {
            return fallback;
        }
        try {
            int start = css.indexOf('(');
            int end = css.lastIndexOf(')');
            if (start < 0 || end < start) {
                return fallback;
            }
            String[] parts = css.substring(start + 1, end).split(",");
            if (parts.length < 3) {
                return fallback;
            }
            int r = clampByte((int) Float.parseFloat(parts[0].trim()));
            int g = clampByte((int) Float.parseFloat(parts[1].trim()));
            int b = clampByte((int) Float.parseFloat(parts[2].trim()));
            int a = 255;
            if (parts.length > 3) {
                a = clampByte(Math.round(Float.parseFloat(parts[3].trim()) * 255f));
            }
            if (a >= 255) {
                return Color.rgb(r, g, b);
            }
            int base = getColorValue(mDarkTheme ? R.color.dark_bg : R.color.light_bg);
            return Color.rgb(
                    blend(Color.red(base), r, a),
                    blend(Color.green(base), g, a),
                    blend(Color.blue(base), b, a));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int blend(int base, int fg, int alpha) {
        return (base * (255 - alpha) + fg * alpha) / 255;
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** 颜色是否偏亮（决定状态栏/导航栏图标用深色还是浅色） */
    private static boolean isLightColor(int color) {
        return Color.luminance(color) > 0.5d;
    }

    /** 页面容器、WebView 与加载动画配色 */
    private void applyViewTheme(boolean dark) {
        int bg = mPageBg != 0 ? mPageBg : getColorValue(dark ? R.color.dark_bg : R.color.light_bg);
        int accent = getColorValue(dark ? R.color.dark_accent : R.color.light_accent);
        if (mRoot != null) {
            mRoot.setBackgroundColor(bg);
        }
        if (mWebView != null) {
            // WebView 默认刷白，这里先铺上网页底色，页面渲染出来前不再白屏
            mWebView.setBackgroundColor(bg);
        }
        if (mSpinner != null) {
            mSpinner.setIndeterminateTintList(ColorStateList.valueOf(accent));
        }
        if (mProgressBar != null) {
            mProgressBar.setProgressTintList(ColorStateList.valueOf(accent));
            mProgressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        }
    }

    @SuppressWarnings("deprecation")
    private int getColorValue(int resId) {
        return getResources().getColor(resId);
    }

    /** 返回 true 表示已拦截（外部浏览器打开），false 表示继续在 WebView 内加载 */
    private boolean handleUrl(String url) {
        if (isInternalUrl(url)) {
            return false; // 同属 flymrp.gddhy.net 域名，WebView 内打开
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private static boolean isInternalUrl(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return false;
        }
        String host = uri.getHost();
        return host != null
                && (INNER_HOST.equals(host) || host.endsWith("." + INNER_HOST));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 网页 Fullscreen API 全屏时，返回键先退出全屏
            if (isFullscreen()) {
                exitFullscreen();
                return true;
            }
            // 拨号键画布全屏时已在 dispatchKeyEvent 里消费掉 BACK，不会走到这里
            return handleBackKey();
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_FROM_MRP, mFromMrp);
        outState.putBoolean(STATE_PLAYER_STARTED, mPlayerStarted);
        mWebView.saveState(outState);
    }

    @Override
    protected void onPause() {
        mWebView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mWebView.onResume();
    }

    @Override
    protected void onDestroy() {
        if (mCustomView != null) {
            exitFullscreen();
        }
        if (mWebView != null) {
            mWebView.removeCallbacks(mInjectRunnable);
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
