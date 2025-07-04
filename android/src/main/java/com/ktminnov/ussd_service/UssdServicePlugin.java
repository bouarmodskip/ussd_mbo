package com.ktminnov.ussd_service;

import static android.Manifest.permission.CALL_PHONE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;

import java.util.concurrent.CompletableFuture;

/**
 * Flutter plugin that sends USSD codes silently via {@link TelephonyManager#sendUssdRequest} and
 * returns the operator response. iOS is not supported.
 * <p>
 * Compatible with Android API 26‑35, Java 17, AGP 8.x, Kotlin 1.9+.
 */
public class UssdServicePlugin implements FlutterPlugin, MethodCallHandler {

  private static final String CHANNEL_NAME = "com.ktminnov.ussd_service/plugin_channel";
  private static final String MAKE_REQUEST_METHOD = "makeRequest";

  private Context context;
  private MethodChannel channel;

  // ---------------------------------------------------------------------------
  // FlutterPlugin lifecycle ----------------------------------------------------
  // ---------------------------------------------------------------------------
  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
    initialize(binding.getApplicationContext(), binding.getBinaryMessenger());
  }

  /**
   * Legacy registration for the v1 embedding. Safe to remove if you only support
   * Flutter 2+ (embedding v2).
   */
  @Deprecated
  @SuppressWarnings("deprecation")
  public static void registerWith(Registrar registrar) {
    UssdServicePlugin instance = new UssdServicePlugin();
    instance.initialize(registrar.context(), registrar.messenger());
  }

  private void initialize(Context context, BinaryMessenger messenger) {
    this.context = context.getApplicationContext();
    this.channel = new MethodChannel(messenger, CHANNEL_NAME);
    this.channel.setMethodCallHandler(this);
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    if (channel != null) channel.setMethodCallHandler(null);
    channel = null;
    context = null;
  }

  // ---------------------------------------------------------------------------
  // Method channel ----------------------------------------------------------------
  // ---------------------------------------------------------------------------
  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
    if (!MAKE_REQUEST_METHOD.equals(call.method)) {
      result.notImplemented();
      return;
    }

    try {
      UssdRequestParams params = new UssdRequestParams(call);
      makeRequest(params)
          .thenAccept(result::success)
          .exceptionally(e -> {
            String msg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            result.error(RequestExecutionException.type, msg, null);
            return null;
          });
    } catch (RequestParamsException e) {
      result.error(RequestParamsException.type, e.message, null);
    } catch (RequestExecutionException e) {
      result.error(RequestExecutionException.type, e.message, null);
    } catch (Exception e) {
      result.error("unknown_exception", e.getMessage(), null);
    }
  }

  // ---------------------------------------------------------------------------
  // USSD logic -----------------------------------------------------------------
  // ---------------------------------------------------------------------------
  @SuppressLint("MissingPermission")
  private CompletableFuture<String> makeRequest(final UssdRequestParams params)
      throws RequestExecutionException {

    if (ContextCompat.checkSelfPermission(context, CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
      throw new RequestExecutionException("CALL_PHONE permission missing");
    }

    CompletableFuture<String> future = new CompletableFuture<>();

    TelephonyManager.UssdResponseCallback callback = new TelephonyManager.UssdResponseCallback() {
      @Override
      public void onReceiveUssdResponse(TelephonyManager telephonyManager, String request, CharSequence response) {
        future.complete(response.toString());
      }

      @Override
      public void onReceiveUssdResponseFailed(TelephonyManager telephonyManager, String request, int failureCode) {
        String reason;
        if (failureCode == TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL) {
          reason = "USSD_ERROR_SERVICE_UNAVAIL";
        } else if (failureCode == TelephonyManager.USSD_RETURN_FAILURE) {
          reason = "USSD_RETURN_FAILURE";
        } else {
          reason = "unknown error";
        }
        future.completeExceptionally(new RequestExecutionException(reason));
      }
    };

    TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    TelephonyManager simManager = telephonyManager.createForSubscriptionId(params.subscriptionId);
    simManager.sendUssdRequest(params.code, callback, new Handler(Looper.getMainLooper()));
    return future;
  }

  // ---------------------------------------------------------------------------
  // Parameter & error helpers --------------------------------------------------
  // ---------------------------------------------------------------------------
  private static class RequestParamsException extends Exception {
    static final String type = "ussd_plugin_incorrect_parameters";
    final String message;

    RequestParamsException(String message) {
      this.message = message;
    }
  }

  private static class RequestExecutionException extends Exception {
    static final String type = "ussd_plugin_ussd_execution_failure";
    final String message;

    RequestExecutionException(String message) {
      this.message = message;
    }
  }

  private static class UssdRequestParams {
    final int subscriptionId;
    final String code;

    UssdRequestParams(@NonNull MethodCall call) throws RequestParamsException {
      Integer subId = call.argument("subscriptionId");
      if (subId == null) {
        throw new RequestParamsException("Parameter `subscriptionId` must be an int");
      }
      if (subId < 0) {
        throw new RequestParamsException("Parameter `subscriptionId` must be >= 0");
      }
      subscriptionId = subId;

      String c = call.argument("code");
      if (c == null) {
        throw new RequestParamsException("Parameter `code` must be a String");
      }
      if (c.isEmpty()) {
        throw new RequestParamsException("Parameter `code` must not be empty");
      }
      code = c;
    }
  }
}
