package net.spidiboost.shist;

import java.util.BitSet;
import java.util.regex.Pattern;

/** Legacy/RGB formatting parser; only the reason value can exclude a ban. */
public final class ReasonColors {
    private static final int[] COLORS={0x000000,0x0000aa,0x00aa00,0x00aaaa,0xaa0000,0xaa00aa,0xffaa00,0xaaaaaa,
            0x555555,0x5555ff,0x55ff55,0x55ffff,0xff5555,0xff55ff,0xffff55,0xffffff};
    private static final Pattern REASON=Pattern.compile("^(?:по причине|причина|reason)\\s*:\\s*(.*)$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private ReasonColors() { }
    public static boolean aqua(int rgb) {
        int r=(rgb>>16)&255,g=(rgb>>8)&255,b=rgb&255;
        return g>=140&&b>=180&&g-r>=35&&b-r>=50;
    }
    public static boolean hasAqua(String raw,boolean continuation) {
        StringBuilder plain=new StringBuilder();BitSet blue=new BitSet();int color=-1;
        for(int i=0;i<raw.length();i++) {
            char c=raw.charAt(i);
            if(c=='§'&&i+1<raw.length()) {
                char code=Character.toLowerCase(raw.charAt(i+1));
                if(code=='x'&&i+13<raw.length()) {
                    int rgb=0;boolean valid=true;
                    for(int j=0;j<6;j++){int p=i+2+j*2,n=Character.digit(raw.charAt(p+1),16);if(raw.charAt(p)!='§'||n<0){valid=false;break;}rgb=(rgb<<4)|n;}
                    if(valid){color=rgb;i+=13;continue;}
                }
                int legacy=Character.digit(code,16);
                if(legacy>=0){color=COLORS[legacy];i++;continue;}
                if(code=='r'){color=-1;i++;continue;}
                if("klmno".indexOf(code)>=0){i++;continue;}
            }
            if(c=='\u200b'||c=='\ufeff')continue;
            if(c=='\u00a0'||c=='\u202f')c=' ';
            if(!Character.isWhitespace(c)&&color>=0&&aqua(color))blue.set(plain.length());
            plain.append(c);
        }
        String visible=plain.toString(),line=ShistEngine.clean(visible);if(line.isEmpty())return false;
        int start=visible.indexOf(line),end=start+line.length();
        if(start<0)return false;
        if(!continuation){var match=REASON.matcher(line);if(!match.matches())return false;start+=match.start(1);end=visible.indexOf(line)+match.end(1);}
        int colored=blue.nextSetBit(Math.max(0,start));return colored>=0&&colored<end;
    }
}
