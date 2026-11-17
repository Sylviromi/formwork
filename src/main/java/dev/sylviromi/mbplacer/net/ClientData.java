package dev.sylviromi.mbplacer.net;

import dev.sylviromi.mbplacer.build.Materials;

import java.util.List;

/** Last material report from the server. Plain data so the network code needs no client classes. */
public final class ClientData {
    public static List<Materials.Row> rows = List.of();
    public static Materials.MeStatus me = Materials.MeStatus.NO_AE2;
    public static String reportFor = "";
    public static int blocks;

    private ClientData() {
    }
}
