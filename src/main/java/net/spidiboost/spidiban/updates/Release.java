package net.spidiboost.spidiban.updates;
import java.io.*;
import java.net.URI;
import java.util.Properties;

public record Release(ModCatalog mod, String version, String artifact, String sha256) {
    public static Release parse(ModCatalog mod, String text) throws IOException {
        var p = new Properties(); p.load(new StringReader(text));
        String v = p.getProperty("version", ""), a = p.getProperty("artifact", ""), h = p.getProperty("sha256", "");
        if (!valid(v) || !"1.21.4".equals(p.getProperty("minecraft")) || !a.equals(mod.artifact(v)) || !h.matches("[a-f0-9]{64}"))
            throw new IOException("Invalid release manifest");
        return new Release(mod, v, a, h);
    }
    public URI download() { return URI.create(mod.base() + "/releases/download/v" + version + "/" + artifact); }
    public boolean newerThan(String current) {
        if (!valid(current)) return false;
        String[] a=version.split("\\."), b=current.split("\\.");
        for (int i=0;i<3;i++) { int n=Integer.compare(Integer.parseInt(a[i]),Integer.parseInt(b[i])); if(n!=0)return n>0; }
        return false;
    }
    private static boolean valid(String v) {return v.matches("(?:0|[1-9][0-9]{0,5})\\.(?:0|[1-9][0-9]{0,5})\\.(?:0|[1-9][0-9]{0,5})");}
}
