package com.reggate.lib;

import android.content.Context;

public final class RegGateResources {

    private static final String PACKAGE_NAME = "com.reggate.lib";

    private RegGateResources() {}

    public static int getLayoutId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "layout", ctx.getPackageName());
    }

    public static int getId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }

    public static int getStringId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
    }

    public static int getColorId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "color", ctx.getPackageName());
    }

    public static int getRawId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "raw", ctx.getPackageName());
    }

    public static int getDrawableId(Context ctx, String name) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        if (id == 0) {
            id = ctx.getResources().getIdentifier(name, "drawable", PACKAGE_NAME);
        }
        return id;
    }

    public static String getString(Context ctx, String name, Object... args) {
        int id = getStringId(ctx, name);
        if (id == 0) return name;
        if (args.length == 0) {
            return ctx.getString(id);
        }
        return ctx.getString(id, args);
    }
}
