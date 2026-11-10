package com.rubikscube;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Genuine state-based 2x2 and 3x3 solver. No move history, network or third-party JAR.
 *
 * 2x2: exact breadth-first distance table for the 3,674,160 corner states with one
 * anchored corner; physical cube orientation is normalized before searching.
 * 3x3: two-phase IDA* with coordinate-move and pair-pruning tables (Kociemba-style
 * subgroup reduction; original implementation). Tables are constructed in memory
 * on the solver worker thread. Not guaranteed minimum-length solutions.
 *
 * This class contains no Android APIs, so it can be tested on the JVM.
 */
public final class SmartCubeSolver {
    private SmartCubeSolver() { }
    public static final int[] FACE = {2, 0, 4, 3, 1, 5}; // U R F D L B mapped to CubeModel face index
    private static final int[][] CF = {
        {2,0,4}, {2,4,1}, {2,1,5}, {2,5,0},
        {3,4,0}, {3,1,4}, {3,5,1}, {3,0,5}
    };
    private static final int[][] EF = {
        {2,0}, {2,4}, {2,1}, {2,5},
        {3,0}, {3,4}, {3,1}, {3,5},
        {4,0}, {4,1}, {5,1}, {5,0}
    };
    private static final int[] FACT = {1,1,2,6,24,120,720,5040,40320};
    private static final int[][] CHOOSE = new int[13][5];
    private static final int[] TWO_POS = {0,1,2,3,4,5,7}; // DBL corner #6 held still
    private static final int[] PHASE2 = {0,1,2,9,10,11,4,7,13,16};
    private static final int MAX_PHASE_ONE = 12;
    private static final int MAX_PHASE_TWO = 18;
    static {
        for (int i = 0; i <= 12; i++) {
            CHOOSE[i][0] = 1;
            for (int j = 1; j <= 4; j++) {
                CHOOSE[i][j] = i == 0 ? 0 : CHOOSE[i - 1][j] + CHOOSE[i - 1][j - 1];
            }
        }
    }

    public interface Cancellation { boolean cancelled(); }
    public static final class Result {
        public final List<int[]> moves;
        public final String explanation;
        Result(List<int[]> moves, String explanation) {
            this.moves = moves;
            this.explanation = explanation;
        }
    }

    private static final class C {
        int[] cp = new int[8], co = new int[8];
        int[] ep = new int[12], eo = new int[12];
        C() { for (int i=0;i<8;i++) cp[i]=i; for(int i=0;i<12;i++)ep[i]=i; }
        C copy() { C x=new C(); System.arraycopy(cp,0,x.cp,0,8);System.arraycopy(co,0,x.co,0,8);
            System.arraycopy(ep,0,x.ep,0,12);System.arraycopy(eo,0,x.eo,0,12);return x; }
    }
    private static CubeModel cloneModel(CubeModel m) {
        CubeModel out = new CubeModel(m.n);
        System.arraycopy(m.pos,0,out.pos,0,m.pos.length);
        System.arraycopy(m.nrm,0,out.nrm,0,m.nrm.length);
        System.arraycopy(m.col,0,out.col,0,m.col.length);
        System.arraycopy(m.nAxis,0,out.nAxis,0,m.nAxis.length);
        return out;
    }
    private static int sign(int face) { return face == 0 || face == 2 || face == 4 ? 1 : -1; }
    private static int faceAxis(int face) { return face / 2; }
    private static int[][] findFacelets(CubeModel m, int[][] faces) {
        int n = faces.length;
        int[][] result = new int[n][];
        int max = m.n - 1;
        for (int p=0;p<n;p++) {
            result[p] = new int[faces[p].length];
            Arrays.fill(result[p],-1);
            for (int i=0;i<m.count;i++) {
                int o=i*3;
                boolean position=true;
                for (int axis=0;axis<3;axis++) {
                    int target=0;
                    for(int j=0;j<faces[p].length;j++) if(faceAxis(faces[p][j])==axis)
                        target=sign(faces[p][j])*max;
                    if(m.pos[o+axis]!=target) { position=false;break; }
                }
                if (!position) continue;
                for (int k=0;k<faces[p].length;k++) if(m.faceOf(i)==faces[p][k]) {
                    result[p][k]=m.col[i];break;
                }
            }
            for(int k=0;k<faces[p].length;k++) if(result[p][k]<0)
                throw new IllegalArgumentException("Missing facelet at cubie "+p);
        }
        return result;
    }
    private static C extract(CubeModel m) {
        if(m.n!=2 && m.n!=3)throw new IllegalArgumentException("Only 2x2 and 3x3");
        int[] colorToFace = new int[6];
        if (m.n == 2) { for(int f=0;f<6;f++) colorToFace[f]=f; }
        else {
            Arrays.fill(colorToFace,-1);
            for(int i=0;i<m.count;i++) {
                int o=i*3, zeros=0;
                for(int k=0;k<3;k++)if(m.pos[o+k]==0)zeros++;
                if(zeros==2)colorToFace[m.col[i]]=m.faceOf(i);
            }
            for(int c:colorToFace)if(c<0)
                throw new IllegalArgumentException("Invalid centre colours");
        }
        C state=new C();
        int[][] fc=findFacelets(m,CF);
        boolean[] seen=new boolean[8];
        int sum=0;
        for(int pos=0;pos<8;pos++) {
            for(int k=0;k<3;k++) fc[pos][k]=colorToFace[fc[pos][k]];
            int ori=-1;
            for(int j=0;j<3;j++)if(fc[pos][j]==2 || fc[pos][j]==3)ori=j;
            if(ori<0)throw new IllegalArgumentException("Corner without top/bottom colour");
            int a=fc[pos][(ori+1)%3],b=fc[pos][(ori+2)%3];
            int piece=-1;
            for(int q=0;q<8;q++)if(CF[q][1]==a && CF[q][2]==b) {piece=q;break;}
            if(piece<0||seen[piece])throw new IllegalArgumentException("Invalid corner pieces");
            seen[piece]=true;state.cp[pos]=piece;state.co[pos]=ori;sum+=ori;
        }
        if(sum%3!=0)throw new IllegalArgumentException("Impossible corner twists");
        if(m.n==3) {
            int[][] fe=findFacelets(m,EF);
            seen=new boolean[12];
            sum=0;
            for(int pos=0;pos<12;pos++) {
                int a=colorToFace[fe[pos][0]],b=colorToFace[fe[pos][1]];
                int piece=-1,flip=-1;
                for(int q=0;q<12;q++) {
                    if(EF[q][0]==a && EF[q][1]==b) {piece=q;flip=0;break;}
                    if(EF[q][1]==a && EF[q][0]==b) {piece=q;flip=1;break;}
                }
                if(piece<0||seen[piece])throw new IllegalArgumentException("Invalid edges");
                seen[piece]=true;state.ep[pos]=piece;state.eo[pos]=flip;sum+=flip;
            }
            if(sum%2!=0)throw new IllegalArgumentException("Impossible edge flips");
            if((parity(state.cp)^parity(state.ep))!=0)
                throw new IllegalArgumentException("Impossible corner/edge parity");
        }
        return state;
    }
    private static int parity(int[] p) {
        int r=0;for(int i=0;i<p.length;i++)for(int j=i+1;j<p.length;j++)if(p[i]>p[j])r^=1;return r;
    }
    private static final class Tables {
        final C[] effect=new C[18];
        final int[] twist=new int[2187*18],flip=new int[2048*18],slice=new int[495*18];
        final int[] cornerP=new int[40320*10],edgeP=new int[40320*10],sliceP=new int[24*10];
        byte[] twistPrune,flipPrune,cornerPrune,edgePrune;
        int[] twoPerm,twoTwist;
        byte[] twoDistance;
        Tables() {
            CubeModel m=new CubeModel(3);
            for(int f=0;f<6;f++) {
                m.reset(3);
                int[] mv=m.faceMove(FACE[f],1,true);
                for(int turns=0;turns<3;turns++) {
                    m.apply(mv);effect[f*3+turns]=extract(m);
                }
            }
        }
        synchronized void prepare3(Cancellation cancel) {
            if(twistPrune!=null)return;
            for(int i=0;i<2187;i++) {
                C s=new C();decodeTwist(i,s.co);
                for(int m=0;m<18;m++)twist[i*18+m]=twistIndex(apply(s,effect[m]).co);
            }
            for(int i=0;i<2048;i++) {
                C s=new C();decodeFlip(i,s.eo);
                for(int m=0;m<18;m++)flip[i*18+m]=flipIndex(apply(s,effect[m]).eo);
            }
            for(int i=0;i<495;i++) {
                C s=new C();decodeSlice(i,s.ep);
                for(int m=0;m<18;m++)slice[i*18+m]=sliceIndex(apply(s,effect[m]).ep);
            }
            for(int i=0;i<40320;i++) {
                C s=new C();decodePerm(i,8,s.cp,0);
                for(int j=0;j<10;j++)cornerP[i*10+j]=permIndex(apply(s,effect[PHASE2[j]]).cp,0,8);
                C e=new C();decodePerm(i,8,e.ep,0);
                for(int j=0;j<10;j++)edgeP[i*10+j]=permIndex(apply(e,effect[PHASE2[j]]).ep,0,8);
            }
            for(int i=0;i<24;i++) {
                C s=new C();decodePerm(i,4,s.ep,8);
                for(int j=0;j<10;j++)sliceP[i*10+j]=permIndex(apply(s,effect[PHASE2[j]]).ep,8,4);
            }
            if (cancel.cancelled())return;
            byte[] a=pairPrune(2187,495,18,twist,slice,0,494,cancel);
            byte[] b=pairPrune(2048,495,18,flip,slice,0,494,cancel);
            byte[] c=pairPrune(40320,24,10,cornerP,sliceP,0,0,cancel);
            byte[] d=pairPrune(40320,24,10,edgeP,sliceP,0,0,cancel);
            if(!cancel.cancelled()&&a!=null&&b!=null&&c!=null&&d!=null) {
                twistPrune=a;flipPrune=b;cornerPrune=c;edgePrune=d;
            }
        }
        synchronized void prepare2(Cancellation cancel) {
            if(twoDistance!=null)return;
            twoPerm=new int[5040*9];twoTwist=new int[729*9];
            int[] moves={0,1,2,3,4,5,6,7,8}; // U R F (each 1,2,3 turns)
            for(int i=0;i<5040;i++) {
                C c=new C();int[] p=new int[7];decodePerm(i,7,p,0);
                for(int k=0;k<7;k++)c.cp[TWO_POS[k]]=TWO_POS[p[k]];
                for(int j=0;j<9;j++)twoPerm[i*9+j]=twoPermIndex(apply(c,effect[moves[j]]).cp);
            }
            for(int i=0;i<729;i++) {
                C c=new C();decodeTwoTwist(i,c.co);
                for(int j=0;j<9;j++)twoTwist[i*9+j]=twoTwistIndex(apply(c,effect[moves[j]]).co);
            }
            if(cancel.cancelled())return;
            byte[] dist=new byte[5040*729];Arrays.fill(dist,(byte)-1);
            int[] queue=new int[dist.length];queue[0]=0;dist[0]=0;
            int h=0,t=1;
            while(h<t) {
                int id=queue[h++],p=id/729,tw=id%729;
                for(int j=0;j<9;j++) {
                    int next=twoPerm[p*9+j]*729+twoTwist[tw*9+j];
                    if(dist[next]<0) {dist[next]=(byte)(dist[id]+1);queue[t++]=next;}
                }
                if((h & 16383)==0&&cancel.cancelled())return;
            }
            if(t!=dist.length)throw new IllegalStateException("Incomplete 2x2 move graph: "+t);
            twoDistance=dist;
        }
    }
    private static byte[] pairPrune(int countA,int countB,int moves,int[] moveA,int[] moveB,
                                    int goalA,int goalB,Cancellation cancel) {
        byte[] dist=new byte[countA*countB];Arrays.fill(dist,(byte)-1);
        int[] queue=new int[dist.length];int head=0,tail=1;
        int root=goalA*countB+goalB;queue[0]=root;dist[root]=0;
        while(head<tail) {
            int id=queue[head++],a=id/countB,b=id%countB;
            for(int j=0;j<moves;j++) {
                int na=moveA[a*moves+j],nb=moveB[b*moves+j];
                int ni=na*countB+nb;
                if(dist[ni]<0) {dist[ni]=(byte)(dist[id]+1);queue[tail++]=ni;}
            }
            if((head&16383)==0 && cancel.cancelled())return null;
        }
        return dist;
    }
    private static C apply(C src,C move) {
        C out=new C();
        for(int i=0;i<8;i++) {
            int from=move.cp[i];out.cp[i]=src.cp[from];out.co[i]=(src.co[from]+move.co[i])%3;
        }
        for(int i=0;i<12;i++) {
            int from=move.ep[i];out.ep[i]=src.ep[from];out.eo[i]=src.eo[from]^move.eo[i];
        }
        return out;
    }
    private static int permIndex(int[] perm,int start,int n) {
        int ans=0;
        for(int i=0;i<n;i++) {
            int less=0;for(int j=i+1;j<n;j++)if(perm[start+j]<perm[start+i])less++;
            ans+=less*FACT[n-1-i];
        }
        return ans;
    }
    private static void decodePerm(int idx,int n,int[] dest,int start) {
        int[] items=new int[n];for(int i=0;i<n;i++)items[i]=i+start;
        for(int i=0;i<n;i++) {
            int d=idx/FACT[n-1-i];idx%=FACT[n-1-i];
            dest[start+i]=items[d];
            for(int j=d;j<n-i-1;j++)items[j]=items[j+1];
        }
    }
    private static int twistIndex(int[] co) {
        int id=0;for(int i=0;i<7;i++)id=id*3+co[i];return id;
    }
    private static void decodeTwist(int idx,int[] co) {
        int s=0;for(int i=6;i>=0;i--){co[i]=idx%3;s+=co[i];idx/=3;}
        co[7]=(3-s%3)%3;
    }
    private static int flipIndex(int[] eo) {
        int id=0;for(int i=0;i<11;i++)id=id*2+eo[i];return id;
    }
    private static void decodeFlip(int idx,int[] eo) {
        int s=0;for(int i=10;i>=0;i--){eo[i]=idx%2;s+=eo[i];idx/=2;}
        eo[11]=s%2;
    }
    private static int sliceIndex(int[] ep) {
        int j=0,id=0;
        for(int i=0;i<12;i++)if(ep[i]>=8)id+=CHOOSE[i][++j];
        return id;
    }
    private static void decodeSlice(int id,int[] ep) {
        int mask=0,k=4;
        for(int pos=11;pos>=0&&k>0;pos--)if(CHOOSE[pos][k]<=id){
            mask|=1<<pos;id-=CHOOSE[pos][k];k--;
        }
        int out=0,in=8;
        for(int i=0;i<12;i++)ep[i]=((mask&(1<<i))!=0)?in++:out++;
    }
    private static int twoPermIndex(int[] cp) {
        int[] p=new int[7];
        for(int i=0;i<7;i++) {
            p[i]=0;
            for(int j=0;j<7;j++)if(TWO_POS[j]==cp[TWO_POS[i]])p[i]=j;
        }
        return permIndex(p,0,7);
    }
    private static int twoTwistIndex(int[] co) {
        int out=0;for(int i=0;i<6;i++)out=out*3+co[TWO_POS[i]];return out;
    }
    private static void decodeTwoTwist(int id,int[] co) {
        int s=0;for(int i=5;i>=0;i--){co[TWO_POS[i]]=id%3;s+=co[TWO_POS[i]];id/=3;}
        co[6]=0;co[7]=(3-s%3)%3;
    }

    private static final class Search {
        final Tables t; final Cancellation cancel;
        final C initial;
        final int[] one=new int[16],two=new int[22];
        List<Integer> result;
        int expansions=0;
        Search(Tables t,C initial,Cancellation cancel){this.t=t;this.initial=initial;this.cancel=cancel;}
        boolean phase1(int tw,int fl,int sl,int remain,int lastFace,int level) {
            if(++expansions%4096==0&&cancel.cancelled())return false;
            int h=Math.max(t.twistPrune[tw*495+sl]&255,t.flipPrune[fl*495+sl]&255);
            if(h>remain)return false;
            if(remain==0) {
                if(tw!=0||fl!=0||sl!=494)return false;
                C s=initial.copy();for(int k=0;k<level;k++)s=apply(s,t.effect[one[k]]);
                int cp=permIndex(s.cp,0,8),ep=permIndex(s.ep,0,8);
                int sp=permIndex(s.ep,8,4);
                for(int depth=0;depth<=MAX_PHASE_TWO;depth++) {
                    if(phase2(cp,ep,sp,depth,-1,0)) {
                        result=new ArrayList<Integer>();for(int k=0;k<level;k++)result.add(one[k]);
                        for(int k=0;k<depth;k++)result.add(PHASE2[two[k]]);
                        return true;
                    }
                    if(cancel.cancelled())return false;
                }
                return false;
            }
            for(int f=0;f<6;f++) {
                if(f==lastFace || (lastFace>=0 && f==lastFace-3))continue;
                for(int d=0;d<3;d++) {
                    int mv=f*3+d;
                    one[level]=mv;
                    if(phase1(t.twist[tw*18+mv],t.flip[fl*18+mv],t.slice[sl*18+mv],remain-1,f,level+1))return true;
                }
            }
            return false;
        }
        boolean phase2(int cp,int ep,int sp,int remain,int lastFace,int level) {
            if(++expansions%4096==0&&cancel.cancelled())return false;
            int h=Math.max(t.cornerPrune[cp*24+sp]&255,t.edgePrune[ep*24+sp]&255);
            if(h>remain)return false;
            if(remain==0)return cp==0&&ep==0&&sp==0;
            for(int j=0;j<10;j++) {
                int f=PHASE2[j]/3;
                if(f==lastFace || (lastFace>=0 && f==lastFace-3))continue;
                two[level]=j;
                if(phase2(t.cornerP[cp*10+j],t.edgeP[ep*10+j],t.sliceP[sp*10+j],remain-1,f,level+1))return true;
            }
            return false;
        }
    }
    private static volatile Tables shared;
    private static final Object LOCK=new Object();
    private static Tables getTables() {
        Tables t=shared;
        if(t!=null)return t;
        synchronized(LOCK) { if(shared==null)shared=new Tables();return shared; }
    }
    /** Called only on a background thread. Returns null on cancellation. */
    public static Result solve(CubeModel model,Cancellation cancel) {
        if(model.n!=2 && model.n!=3)throw new IllegalArgumentException("Smart Solve supports 2x2 and 3x3 only");
        if(cancel.cancelled())return null;
        if(model.isSolved())return new Result(new ArrayList<int[]>(),"Already solved");
        Tables t=getTables();
        if(model.n==2) {
            t.prepare2(cancel);
            if(cancel.cancelled() || t.twoDistance==null)return null;
            return solve2(model,t,cancel);
        }
        t.prepare3(cancel);
        if(cancel.cancelled() || t.twistPrune==null)return null;
        C s=extract(model);
        Search search=new Search(t,s,cancel);
        for(int depth=0;depth<=MAX_PHASE_ONE;depth++) {
            if(search.phase1(twistIndex(s.co),flipIndex(s.eo),sliceIndex(s.ep),depth,-1,0)) {
                ArrayList<int[]> out=new ArrayList<int[]>();
                // Half turns are represented as two quarter-turns in CubeModel.
                for(Integer m:search.result) {
                    int q=m%3;
                    int[] mv=model.faceMove(FACE[m/3],1,q!=2);
                    out.add(mv);
                    if(q==1)out.add(new int[]{mv[0],mv[1],mv[2]});
                }
                verify(model,out);
                return new Result(out,"3x3 calculated using two-phase search");
            }
            if(cancel.cancelled())return null;
        }
        throw new IllegalStateException("No solution within phase-one bound");
    }
    private static Result solve2(CubeModel model,Tables t,Cancellation cancel) {
        CubeModel normalized=null;
        ArrayList<int[]> prefix=null;
        int best=Integer.MAX_VALUE;
        // Search orientations with up to three principal-axis turns per axis.
        for(int x=0;x<4;x++)for(int y=0;y<4;y++)for(int z=0;z<4;z++) {
            int cost=x+y+z;if(cost>=best)continue;
            CubeModel v=cloneModel(model);
            ArrayList<int[]> rotations=new ArrayList<int[]>();
            for(int axis=0;axis<3;axis++) {
                int n=axis==0?x:axis==1?y:z;
                for(int j=0;j<n;j++)for(int layer=-1;layer<=1;layer+=2) {
                    v.rotate(axis,layer,1);rotations.add(new int[]{axis,layer,1});
                }
            }
            C s=extract(v);
            if(s.cp[6]==6 && s.co[6]==0) {
                normalized=v;prefix=rotations;best=cost;
            }
        }
        if(normalized==null)throw new IllegalStateException("Cannot orient reference corner");
        C state=extract(normalized);
        int id=twoPermIndex(state.cp)*729+twoTwistIndex(state.co);
        ArrayList<int[]> moves=prefix;
        int[] fm={0,1,2};
        while(id!=0) {
            if(cancel.cancelled())return null;
            int depth=t.twoDistance[id]&255;
            int p=id/729,tw=id%729;
            boolean found=false;
            for(int m=0;m<9;m++) {
                int next=t.twoPerm[p*9+m]*729+t.twoTwist[tw*9+m];
                if((t.twoDistance[next]&255)==depth-1) {
                    int[] mv=model.faceMove(FACE[fm[m/3]],1,m%3!=2);
                    moves.add(mv);
                    if(m%3==1)moves.add(new int[]{mv[0],mv[1],mv[2]});
                    id=next;found=true;break;
                }
            }
            if(!found)throw new IllegalStateException("2x2 distance table error");
        }
        verify(model,moves);
        return new Result(moves,"2x2 calculated using exact corner-state search");
    }
    private static void verify(CubeModel model,List<int[]> moves) {
        CubeModel v=cloneModel(model);
        for(int[] mv:moves)v.apply(mv);
        if(!v.isSolved())throw new IllegalStateException("Solver validation failed; puzzle left unchanged");
    }
}
