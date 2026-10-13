package net.spidiboost.shist;

import java.util.Optional;
import net.minecraft.text.Text;

/** Preserve inherited server Text colors across semantic newlines, never visual wrapping. */
public final class StyledMessages {
    private StyledMessages() { }
    public static String formatted(Text message) {
        StringBuilder result=new StringBuilder();
        message.visit((style,value)->{
            String prefix="§r";
            if(style.getColor()!=null){var code=new StringBuilder("§x");int rgb=style.getColor().getRgb();for(int shift=20;shift>=0;shift-=4)code.append('§').append(Character.forDigit((rgb>>shift)&15,16));prefix=code.toString();}
            result.append(prefix).append(value.replace("\n","\n"+prefix));
            return Optional.empty();
        },net.minecraft.text.Style.EMPTY);
        return result.toString();
    }
}
