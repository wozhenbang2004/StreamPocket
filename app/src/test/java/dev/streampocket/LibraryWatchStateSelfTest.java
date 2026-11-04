package dev.streampocket;

/** Offline boundary checks; also run by LibraryWatch050Test under JUnit. */
public final class LibraryWatchStateSelfTest {
    private static int checks;
    public static void main(String[] args) {
        checks=0;
        state(0,0,false,false);state(0,200,false,false);state(-1,100_000,false,false);
        state(1,0,false,true);state(9000,-1,false,true);
        state(1,200,false,true);state(189,200,false,true);state(190,200,true,false);state(200,200,true,false);
        state(1,1,true,false);state(18,19,false,true);state(19,19,true,false);
        state(9499,10_000,false,true);state(9500,10_000,true,false);
        state(99_499,100_000,false,true);state(99_500,100_000,true,false);state(100_001,100_000,true,false);
        state(Long.MAX_VALUE-501,Long.MAX_VALUE,false,true);state(Long.MAX_VALUE-500,Long.MAX_VALUE,true,false);
        check("all".equals(LibraryWatchState.normalizeFilter(null)),"null defaults to all");
        check("all".equals(LibraryWatchState.normalizeFilter("watched OR 1=1")),"filter values are whitelisted");
        check("1=1".equals(LibraryWatchState.sql("watched OR 1=1")),"unknown filter cannot inject SQL");
        for(String filter:new String[]{"all","continue","unwatched","watched"})
            check(filter.equals(LibraryWatchState.normalizeFilter(filter)),"known filter "+filter);
        System.out.println("LibraryWatchStateSelfTest: "+checks+" checks passed");
    }
    private static void state(long position,long duration,boolean watched,boolean continuing) {
        check(LibraryWatchState.isWatched(position,duration)==watched,"watched at "+position+"/"+duration);
        check(LibraryWatchState.isContinuing(position,duration)==continuing,"continue at "+position+"/"+duration);
    }
    private static void check(boolean condition,String label) {checks++;if(!condition)throw new AssertionError(label);}
}
