package hamlah.pin.complice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.v4.util.Pair;
import android.util.Log;

import com.bluelinelabs.logansquare.LoganSquare;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import hamlah.pin.App;
import hamlah.pin.BuildConfig;
import hamlah.pin.CompliceListActivity;
import hamlah.pin.service.Settings;
import rx.Observable;

public class Complice {
    private static final String TAG = Complice.class.getSimpleName();
    private static Complice instance;
    private CompliceAPI api = App.app().http().create(CompliceAPI.class);

    public static Complice get() {
        if (instance == null) {
            synchronized (Complice.class) {
                if (instance == null) {
                    instance = new Complice();
                }
            }
        }
        return instance;
    }


    public boolean isLoggedIn() {
        return new Settings(App.app()).getCompliceToken() != null;
    }

    // step 3: direct send POST https://complice.co/oauth/token?code=%3$s&grant_type=authorization_code&client_id=%1$s&username=%1$s&password=%2$s&client_secret=%2$s&redirect_uri=http://127.0.0.1/pinstickytimers/oauth
    // step 4: response: {"access_token":"...","token_type":"Bearer"}
    // step 5: save access token to settings
    //
    // POST /api/u/completeById/$ID

    public Observable<Boolean> completeLogin(Uri uri) {
        String token = uri.getQueryParameter("code");
        return api.authorize(token,
                BuildConfig.CLIENT_ID,
                BuildConfig.CLIENT_SECRET,
                String.format("http://%1$s%2$s",
                        BuildConfig.REDIRECT_URI_HOST, BuildConfig.REDIRECT_URI_PATH))
                .map(authResponse -> {
                    Log.i(TAG, "Your password: " + authResponse.accessToken);
                    new Settings(App.app()).setCompliceToken(authResponse.accessToken);
                    return true;
                });

    }

    public Uri getLoginUrl() {
        return Uri.parse(String.format(
                "https://complice.co/oauth/authorize?response_type=code&client_id=%1$s&client_secret=%2$s&redirect_uri=http://%3$s%4$s",
                BuildConfig.CLIENT_ID,
                BuildConfig.CLIENT_SECRET,
                BuildConfig.REDIRECT_URI_HOST,
                BuildConfig.REDIRECT_URI_PATH
        ));
    }

    public void launchLogin(Context context) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(getLoginUrl());
        context.startActivity(i);
    }

    public Observable<CompliceTask> getNextAction() {
        return getAuthToken()
                .flatMap(api::loadCurrentTask)
                .flatMap(r -> Observable.create((Observable.OnSubscribe<CurrentTaskResponse>) s -> {
                    try {
                        String result = r.string();

                        s.onNext(LoganSquare.parse(result, CurrentTaskResponse.class));
                    } catch (IOException e) {
                        s.onError(e);
                    }
                    s.onCompleted();
                }))
                .map(currentTaskResponse -> {
                    if (currentTaskResponse.noIntentions) {
                        return new CompliceEditTask(App.app(), true, true);
                    }
                    CompliceTask preferredTask = new Settings(App.app()).getPreferredCompliceTask();
                    if (preferredTask != null) {
                        return preferredTask;
                    }
                    return new CompliceRemoteTask(
                            currentTaskResponse.colors != null
                                    ? currentTaskResponse.colors.getIntColor()
                                    : 0x666666,
                            currentTaskResponse.nextAction.text,
                            currentTaskResponse.nextAction.id,
                            currentTaskResponse.nextAction.goalCode);
                });
    }

    @NonNull
    private Observable<String> getAuthToken() {
        String token = new Settings(App.app()).getCompliceToken();
        if (token == null) {
            return Observable.error(new RuntimeException("Not logged in lol"));
        }
        return Observable.just("Bearer " + token);
    }


    public Observable<String> finishAction(CompliceRemoteTask compliceRemoteTask) {
        return getAuthToken()
                .flatMap(token -> api.complete(token, compliceRemoteTask.getId()))
                .flatMap(r -> Observable.create(s -> {
                    try {
                        s.onNext(r.string());
                    } catch (IOException e) {
                        s.onError(e);
                    }
                    s.onCompleted();
                }));
    }

    public void launchEdit(Context context) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setData(Uri.parse(BuildConfig.COMPLICE_TODAY_URL));
        context.startActivity(i);
    }

    public Observable<ArrayList<CompliceRemoteTask>> getActionList() {

        Observable<CompliceList> list = getAuthToken()
                .flatMap(token -> api.loadToday(token))
                .flatMap(response -> Observable.create(s -> {
                            try {
                                final String string = response.string();
                                CompliceList result = LoganSquare.parse(string, CompliceList.class);
                                s.onNext(result);
                            } catch (IOException e) {
                                s.onError(e);
                            }
                            s.onCompleted();
                        }));
        Observable<HashMap<String,Integer>> colors = getGoals()
                .map(goals -> {
                    HashMap<String,Integer> colorMap = new HashMap<>();
                    for (CompliceGoalResponseItem item : goals) {
                        colorMap.put(item.code, item.getIntColor());
                    }
                    return colorMap;
                });

        // FIXME: for now, combine with getGoals() so we get colors. malcolm said he'd add them here?
        return Observable.combineLatest(list, colors, Pair::new)
                .map(pair -> {
                    ArrayList<CompliceRemoteTask> tasks = new ArrayList<>(pair.first.todolist.size());
                    for (CompliceList.CompliceTodoItem item : pair.first.todolist) {
                        Integer color = pair.second.get(item.code);
                        if (color == null) {
                            color = 0;
                        }
                        tasks.add(new CompliceRemoteTask(color, item.text, item.id, item.code,
                                                        item.done, item.nevermind));
                    }
                    return tasks;
                });
    }

    public Observable<List<CompliceGoalResponseItem>> getGoals() {
        return getAuthToken()
                .flatMap(token -> api.loadGoals(token))
                .flatMap(r -> Observable.create(s -> {

                    try {
                        s.onNext(LoganSquare.parseList(r.byteStream(), CompliceGoalResponseItem.class));
                    } catch (IOException e) {
                        s.onError(e);
                    }
                    s.onCompleted();
                }));
    }

    public void setPreferredTask(CompliceRemoteTask item) {

        new Settings(App.app()).setPreferredCompliceTask(item);
    }
}
