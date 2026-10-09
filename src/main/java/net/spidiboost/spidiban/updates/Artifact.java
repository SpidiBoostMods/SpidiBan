package net.spidiboost.spidiban.updates;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipFile;

public final class Artifact {
    public static void validate(Path path, Release r) throws IOException {
        if (!BatchInstall.hash(path).equals(r.sha256())) throw new IOException("Checksum mismatch");
        try (var z=new ZipFile(path.toFile())) {
            var entry=z.getEntry("fabric.mod.json"); if(entry==null)throw new IOException("No Fabric metadata");
            byte[] data;try(var in=z.getInputStream(entry)){data=in.readNBytes(65537);}if(data.length>65536)throw new IOException("Metadata too large");
            var m=JsonParser.parseString(new String(data,StandardCharsets.UTF_8)).getAsJsonObject();
            String mc=m.getAsJsonObject("depends").get("minecraft").getAsString();
            if(!r.mod().id().equals(m.get("id").getAsString())||!r.version().equals(m.get("version").getAsString())
                ||!"client".equals(m.get("environment").getAsString())||!(mc.equals("1.21.4")||mc.equals("~1.21.4"))
                ||!m.get("name").getAsString().equals("spidiboost."+r.mod().name())
                ||m.getAsJsonArray("authors").size()!=1||!m.getAsJsonArray("authors").get(0).getAsString().equals("SpidiBoost")
                ||z.getEntry(r.mod().id()+"-shared-update-agent.jar")==null)throw new IOException("Unexpected release identity");
        }catch(RuntimeException e){throw new IOException("Invalid release metadata",e);}
    }
}
