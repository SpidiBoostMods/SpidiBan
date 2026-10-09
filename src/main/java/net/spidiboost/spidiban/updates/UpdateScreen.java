package net.spidiboost.spidiban.updates;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.*;
import java.util.*;
import java.util.function.Supplier;

/** One screen for the installed family, including modules that are already current. */
public final class UpdateScreen extends Screen {
    private final Supplier<List<UpdateRow>> rows;
    private final String headline, footer;
    private final boolean restarting;
    private final Screen previous;
    public UpdateScreen(Supplier<List<UpdateRow>> rows,String headline,String footer,boolean restarting,Screen previous) {
        super(Text.literal("SpidiBoost — обновления"));this.rows=rows;this.headline=headline;this.footer=footer;this.restarting=restarting;this.previous=previous;
    }
    @Override protected void init() {if(!restarting)addDrawableChild(ButtonWidget.builder(Text.literal("Продолжить"),b->close()).dimensions(width/2-70,height-35,140,20).build());}
    @Override public boolean shouldCloseOnEsc(){return !restarting;}
    @Override public void close(){if(!restarting&&client!=null)client.setScreen(previous);}
    public static Text gradient(String value) {
        var text=Text.empty();int[] cp=value.codePoints().toArray();for(int i=0;i<cp.length;i++) {
            double t=cp.length<2?0:(double)i/(cp.length-1);int r=(int)(255+(255-255)*t),g=(int)(105+(207-105)*t),b=(int)(191+(133-191)*t);
            text.append(Text.literal(new String(Character.toChars(cp[i]))).styled(s->s.withColor((r<<16)|(g<<8)|b).withBold(true)));
        }return text;
    }
    @Override public void renderBackground(DrawContext c,int mx,int my,float delta) {}
    @Override public void render(DrawContext c,int mx,int my,float delta) {
        c.fillGradient(0,0,width,height,0xff0e1020,0xff171b35);List<UpdateRow> current=rows.get();int spacing=height<240?30:38;
        int block=current.size()*spacing+65,top=Math.max(22,(height-block)/2),left=Math.max(12,width/2-150),right=Math.min(width-12,width/2+150);
        c.fillGradient(left,top-12,right,top-10,0xffab70ff,0xff58d9f7);
        int y=top+5;
        for(var row:current){c.drawCenteredTextWithShadow(textRenderer,gradient(row.name()+" "+row.version()),width/2,y,0xffffff);c.drawCenteredTextWithShadow(textRenderer,Text.literal(row.status()),width/2,y+13,0x8798bf);y+=spacing;}
        int line=y+4;for(var text:textRenderer.wrapLines(Text.literal(headline),Math.max(80,width-32))){c.drawCenteredTextWithShadow(textRenderer,text,width/2,line,0xc3d2ee);line+=11;}
        for(var text:textRenderer.wrapLines(Text.literal(footer),Math.max(80,width-32))){c.drawCenteredTextWithShadow(textRenderer,text,width/2,line+8,0x8798bf);line+=11;}
        super.render(c,mx,my,delta);
    }
}
