package net.spidiboost.spidiban.updates;
import java.io.*;
import java.nio.file.Path;
import java.util.*;

/** Credentials and Unicode paths travel over stdin, never persisted as a restart script. */
public record BatchPlan(long pid, Path game, List<Change> changes, List<Removal> removals, Path cwd, List<String> command, boolean legacyCard) {
    public record Change(Path target, Path destination, Path staged, String oldHash, String newHash) {}
    public record Removal(Path target, String hash) {}
    public BatchPlan { changes=List.copyOf(changes); removals=List.copyOf(removals); command=List.copyOf(command); }
    public void write(OutputStream stream)throws IOException {
        var out=new DataOutputStream(stream);out.writeInt(0x53425531);out.writeLong(pid);out.writeUTF(game.toString());out.writeBoolean(legacyCard);
        out.writeInt(changes.size());for(var c:changes){for(String s:List.of(c.target().toString(),c.destination().toString(),c.staged().toString(),c.oldHash(),c.newHash()))out.writeUTF(s);}
        out.writeInt(removals.size());for(var r:removals){out.writeUTF(r.target().toString());out.writeUTF(r.hash());}
        out.writeUTF(cwd.toString());out.writeInt(command.size());for(String s:command)out.writeUTF(s);out.flush();
    }
    public static BatchPlan read(InputStream stream)throws IOException {
        var in=new DataInputStream(stream);if(in.readInt()!=0x53425531)throw new IOException("Unknown update protocol");
        long pid=in.readLong();Path game=Path.of(in.readUTF());boolean legacy=in.readBoolean();int n=in.readInt();
        if(pid<=0||n<1||n>16)throw new IOException("Invalid batch size");var changes=new ArrayList<Change>();
        for(int i=0;i<n;i++)changes.add(new Change(Path.of(in.readUTF()),Path.of(in.readUTF()),Path.of(in.readUTF()),in.readUTF(),in.readUTF()));
        n=in.readInt();if(n<0||n>1)throw new IOException("Invalid legacy removal count");var removals=new ArrayList<Removal>();for(int i=0;i<n;i++)removals.add(new Removal(Path.of(in.readUTF()),in.readUTF()));
        Path cwd=Path.of(in.readUTF());n=in.readInt();if(n<0||n>4096)throw new IOException("Invalid command size");var cmd=new ArrayList<String>();for(int i=0;i<n;i++)cmd.add(in.readUTF());
        return new BatchPlan(pid,game,changes,removals,cwd,cmd,legacy);
    }
}
