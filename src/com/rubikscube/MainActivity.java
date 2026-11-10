package com.rubikscube;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements CubeView.Listener {

    private static final int MIN_SIZE = 2;
    private static final int MAX_SIZE = 10;

    // timer phases
    private static final int IDLE = 0;    // no timing (fresh / manually built cube)
    private static final int ARMED = 1;   // scrambled, waiting for the first move
    private static final int RUNNING = 2;
    private static final int DONE = 3;

    private static final int[] SLOP_DP = {26, 18, 12};
    private static final int[] SCRAMBLE_LENGTHS = {0, 10, 20, 30, 50};

    private CubeView cube;
    private ShapePuzzleView polygon;
    // 4 = ordinary square cube, 5 = twelve-face pentagonal dodecahedron
    private int activeShape = 4;
    private int pentagonRows = 2;
    private Button shapeButton;
    private Button minusButton;
    private Button plusButton;
    private Button netButton;
    private NetView net;
    private MegaminxNetView pentNet;
    private FrameLayout netHolder;
    private TextView sizeLabel;
    private TextView status;
    private TextView timerText;
    private TextView comparisonText;
    private int lastSolutionMoves = -1;
    private String lastSolutionMode = "";
    private Button undoBtn;
    private Button redoBtn;

    private SharedPreferences prefs;
    private Stats stats;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int phase = IDLE;
    private long startMs;
    private long elapsedMs;
    private boolean assisted;
    private String doneMessage;
    private String hintText;
    private static final class SmartCancel implements SmartCubeSolver.Cancellation {
        volatile boolean stopped;
        @Override public boolean cancelled() { return stopped; }
    }
    private SmartCancel smartCancel;
    private boolean smartBusy;
    private AlertDialog smartProgress;
    private int smartGeneration;

    // settings
    private boolean setHaptics = true;
    private boolean setSound = false;
    private boolean setColorblind = false;
    private boolean setLarge = false;
    private int setSensitivity = 1;
    private int setScrambleIdx = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (phase == RUNNING) {
                timerText.setText(Stats.formatTime(System.currentTimeMillis() - startMs));
                handler.postDelayed(this, 50);
            }
        }
    };

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button makeButton(String text, String description, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setContentDescription(description);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setMaxLines(1);
        b.setEllipsize(null);
        b.setTextSize(setLarge ? 17 : 14);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int minH = dp(setLarge ? 64 : 48);
        b.setMinHeight(minH);
        b.setMinimumHeight(minH);
        b.setPadding(dp(4), 0, dp(4), 0);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout makeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private void addWeighted(LinearLayout row, View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        row.addView(v, lp);
    }

    private void addButtonRows(LinearLayout parent, List<Button> buttons, int perRow) {
        LinearLayout row = null;
        for (int i = 0; i < buttons.size(); i++) {
            if (i % perRow == 0) {
                row = makeRow();
                parent.addView(row);
            }
            addWeighted(row, buttons.get(i));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("rubik", MODE_PRIVATE);
        stats = new Stats(this);
        loadSettings();

        final boolean land = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1B1B22);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                v.setPadding(dp(8) + in.getSystemWindowInsetLeft(),
                        dp(8) + in.getSystemWindowInsetTop(),
                        dp(8) + in.getSystemWindowInsetRight(),
                        dp(8) + in.getSystemWindowInsetBottom());
                return in;
            }
        });

        // ---- left / top column: timer, status, net, cube
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        timerText = new TextView(this);
        timerText.setTextColor(Color.WHITE);
        timerText.setTextSize(30);
        timerText.setGravity(Gravity.CENTER);
        timerText.setPadding(0, dp(4), 0, 0);
        timerText.setContentDescription("Timer");
        main.addView(timerText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        main.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Always-visible, independently tracked move counts for fair comparisons.
        comparisonText = new TextView(this);
        comparisonText.setTextColor(0xFFAFD9FF);
        comparisonText.setTextSize(14);
        comparisonText.setGravity(Gravity.CENTER);
        comparisonText.setPadding(dp(3), 0, dp(3), dp(3));
        comparisonText.setContentDescription("Scramble and solution move counts");
        comparisonText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        main.addView(comparisonText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        net = new NetView(this);
        pentNet = new MegaminxNetView(this);
        netHolder = new FrameLayout(this);
        netHolder.addView(net, new FrameLayout.LayoutParams(-1, -1));
        netHolder.addView(pentNet, new FrameLayout.LayoutParams(-1, -1));
        netHolder.setVisibility(View.GONE);
        pentNet.setVisibility(View.GONE);
        main.addView(netHolder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(land ? 144 : 180)));

        cube = new CubeView(this);
        polygon = new ShapePuzzleView(this);
        FrameLayout puzzleHolder = new FrameLayout(this);
        puzzleHolder.addView(cube, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        puzzleHolder.addView(polygon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        polygon.setVisibility(View.GONE);
        main.addView(puzzleHolder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        net.bind(cube.getModel(), cube.getPalette());
        pentNet.bind(polygon.getModel());

        // ---- controls
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout sizeRow = makeRow();
        minusButton = makeButton("-", "Decrease cube size or pentagon rows", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeSize(-1);
            }
        });
        plusButton = makeButton("+", "Increase cube size or pentagon rows", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeSize(1);
            }
        });
        sizeLabel = new TextView(this);
        sizeLabel.setTextColor(Color.WHITE);
        sizeLabel.setTextSize(20);
        sizeLabel.setGravity(Gravity.CENTER);
        shapeButton = makeButton("Shape", "Choose puzzle shape", new View.OnClickListener() {
            @Override
            public void onClick(View v) { chooseShape(); }
        });
        addWeighted(sizeRow, shapeButton);
        addWeighted(sizeRow, minusButton);
        addWeighted(sizeRow, sizeLabel);
        addWeighted(sizeRow, plusButton);
        panel.addView(sizeRow);

        List<Button> buttons = new ArrayList<Button>();
        final Button scrambleBtn = makeButton("Scramble",
                "Scramble. Tap for a normal scramble, or hold to keep scrambling until you let go",
                new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (puzzleBusy()) return;
                resetTimerState();
                clearSolutionComparison();
                // Begin every scramble from a solved puzzle. This makes its displayed
                // length directly comparable with later player and Smart Solve counts.
                if (activeShape == 4) {
                    cube.reset();
                    cube.scramble(SCRAMBLE_LENGTHS[setScrambleIdx]);
                } else {
                    polygon.reset();
                    polygon.scramble(SCRAMBLE_LENGTHS[setScrambleIdx]);
                }
                updateAll();
            }
        });
        buttons.add(scrambleBtn);
        installScrambleHold(scrambleBtn);
        undoBtn = makeButton("Undo", "Undo last move", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (puzzleBusy()) return;
                if (activeShape == 4) cube.undo();
                else polygon.undo();
            }
        });
        buttons.add(undoBtn);
        redoBtn = makeButton("Redo", "Redo move", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (puzzleBusy()) return;
                if (activeShape == 4) cube.redo();
                else polygon.redo();
            }
        });
        buttons.add(redoBtn);
        buttons.add(makeButton("Reset", "Reset the cube to solved", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (puzzleBusy()) return;
                resetTimerState();
                clearSolutionComparison();
                if (activeShape == 4) cube.reset();
                else polygon.reset();
            }
        }));
        buttons.add(makeButton("Hint", "Show the next moves", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHint();
            }
        }));
        buttons.add(makeButton("Solve", "Choose Smart Solve or History Solve", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSolveOptions();
            }
        }));
        netButton = makeButton("Net", "Show or hide the flat face map",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        netHolder.setVisibility(netHolder.getVisibility() == View.VISIBLE
                                ? View.GONE : View.VISIBLE);
                    }
                });
        buttons.add(netButton);
        buttons.add(makeButton("More", "More options", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (puzzleBusy()) return;
                showMore();
            }
        }));
        addButtonRows(panel, buttons, land ? 2 : 4);

        if (land) {
            root.addView(main, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            ScrollView scroll = new ScrollView(this);
            scroll.addView(panel);
            root.addView(scroll, new LinearLayout.LayoutParams(dp(setLarge ? 340 : 280),
                    LinearLayout.LayoutParams.MATCH_PARENT));
        } else {
            root.addView(main, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            root.addView(panel);
        }
        setContentView(root);

        applySettingsToViews();

        // ---- restore
        cube.setListener(this);
        polygon.setListener(this);
        String saved = prefs.getString("stateSquare", prefs.getString("state", null));
        if (saved != null) cube.restoreState(saved);
        int restoredShape = prefs.getInt("puzzleShape", 4);
        if (restoredShape != 4 && restoredShape != 5) restoredShape = 5;
        activateShape(restoredShape, false);
        phase = prefs.getInt("phase", IDLE);
        elapsedMs = prefs.getLong("elapsed", 0);
        assisted = prefs.getBoolean("assisted", false);
        if (phase == DONE) phase = IDLE;
        if (phase == ARMED && puzzleSolved()) phase = IDLE;
        updateSizeLabel();
        updateAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phase == RUNNING) {
            startMs = System.currentTimeMillis() - elapsedMs;
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    protected void onPause() {
        if (holdScrambleStart != null) handler.removeCallbacks(holdScrambleStart);
        stopHoldScramble();
        if (smartBusy) cancelSmartSolve();
        super.onPause();
        handler.removeCallbacks(tick);
        if (phase == RUNNING) elapsedMs = System.currentTimeMillis() - startMs;
        if (activeShape == 4) cube.flushPendingMoves();
        else polygon.flushPendingMoves();
        persistActivePuzzle();
        prefs.edit()
                .putInt("puzzleShape", activeShape)
                .putInt("phase", phase)
                .putLong("elapsed", elapsedMs)
                .putBoolean("assisted", assisted)
                .apply();
    }

    // ------------------------------------------------------------------ settings

    private void loadSettings() {
        setHaptics = prefs.getBoolean("haptics", true);
        setSound = prefs.getBoolean("sound", false);
        setColorblind = prefs.getBoolean("colorblind", false);
        setLarge = prefs.getBoolean("large", false);
        setSensitivity = prefs.getInt("sensitivity", 1);
        setScrambleIdx = prefs.getInt("scrambleIdx", 0);
        pentagonRows = prefs.getInt("pentagonRows", 2);
        if (pentagonRows < ShapePuzzleModel.MIN_ROWS
                || pentagonRows > ShapePuzzleModel.MAX_ROWS) pentagonRows = 2;
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean("haptics", setHaptics)
                .putBoolean("sound", setSound)
                .putBoolean("colorblind", setColorblind)
                .putBoolean("large", setLarge)
                .putInt("sensitivity", setSensitivity)
                .putInt("scrambleIdx", setScrambleIdx)
                .apply();
    }

    private void applySettingsToViews() {
        cube.setFeedback(setHaptics, setSound);
        cube.setColorblind(setColorblind);
        cube.setSlopDp(SLOP_DP[setSensitivity]);
        polygon.setFeedback(setHaptics, setSound);
        polygon.setColorblind(setColorblind);
        polygon.setSlopDp(SLOP_DP[setSensitivity]);
        net.setPalette(cube.getPalette());
        pentNet.setColorblind(setColorblind);
    }

    private void showSettings() {
        final String[] items = {"Feedback and display", "Drag sensitivity", "Scramble length"};
        new AlertDialog.Builder(this).setTitle("Settings")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) showToggles();
                        else if (which == 1) showSensitivity();
                        else showScrambleLength();
                    }
                }).show();
    }

    private void showToggles() {
        final String[] names = {"Haptic feedback", "Click sound", "Colorblind-friendly colors",
                "Large controls"};
        final boolean[] vals = {setHaptics, setSound, setColorblind, setLarge};
        new AlertDialog.Builder(this).setTitle("Feedback and display")
                .setMultiChoiceItems(names, vals,
                        new android.content.DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int i,
                                                boolean checked) {
                                vals[i] = checked;
                            }
                        })
                .setPositiveButton("OK", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        boolean largeChanged = setLarge != vals[3];
                        setHaptics = vals[0];
                        setSound = vals[1];
                        setColorblind = vals[2];
                        setLarge = vals[3];
                        saveSettings();
                        applySettingsToViews();
                        if (largeChanged) recreate();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void showSensitivity() {
        String[] names = {"Low (needs a longer drag)", "Medium", "High (short drag turns)"};
        new AlertDialog.Builder(this).setTitle("Drag sensitivity")
                .setSingleChoiceItems(names, setSensitivity,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                setSensitivity = w;
                                saveSettings();
                                applySettingsToViews();
                                d.dismiss();
                            }
                        }).show();
    }

    private void showScrambleLength() {
        String[] names = {"Automatic (by size)", "10 moves", "20 moves", "30 moves", "50 moves"};
        new AlertDialog.Builder(this).setTitle("Scramble length")
                .setSingleChoiceItems(names, setScrambleIdx,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                setScrambleIdx = w;
                                saveSettings();
                                d.dismiss();
                            }
                        }).show();
    }

    // ------------------------------------------------------------------ menu actions

    private void showMore() {
        final String[] items = {"Statistics", "Apply moves (notation)", "How to play",
                "Settings", "Share last solve"};
        new AlertDialog.Builder(this).setTitle("More")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        switch (which) {
                            case 0: showStats(); break;
                            case 1: showApplyMoves(); break;
                            case 2: showTutorial(); break;
                            case 3: showSettings(); break;
                            default: shareLast(); break;
                        }
                    }
                }).show();
    }

    private void showStats() {
        final int size = statsId();
        final String label = activeShape == 4 ? cube.getSize() + "x" + cube.getSize()
                : "Megaminx (" + pentagonRows + " rows)";
        new AlertDialog.Builder(this).setTitle("Statistics")
                .setMessage(stats.summary(size))
                .setPositiveButton("Close", null)
                .setNegativeButton("Clear " + label,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                stats.clear(size);
                            }
                        }).show();
    }

    private void showApplyMoves() {
        final EditText input = new EditText(this);
        input.setHint(activeShape == 4 ? "R U R' U' F2 2R x" : "F1 F2L2 F3' F12");
        input.setSingleLine(false);
        input.setContentDescription("Puzzle move notation");
        new AlertDialog.Builder(this).setTitle("Apply moves")
                .setMessage(activeShape == 4
                        ? "Faces U D L R F B, ' = counter-clockwise, 2 = half turn, "
                            + "2R = second layer, x y z = rotate whole cube."
                        : "Dodecahedron faces F1 to F12. Independent inner slices: "
                            + "F3L2 or F3L3 (where rows permit). Apostrophe reverses the "
                            + "72-degree turn; F3x2 turns face 3 twice.")
                .setView(input)
                .setPositiveButton("Apply", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            if (activeShape == 4) cube.applyAlgorithm(input.getText().toString());
                            else polygon.applyAlgorithm(input.getText().toString());
                        } catch (IllegalArgumentException ex) {
                            Toast.makeText(MainActivity.this, ex.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ hold-to-scramble

    private static final long HOLD_SCRAMBLE_MS = 350;
    private Runnable holdScrambleStart;
    private boolean holdTimerFired;
    private boolean holdScrambleActive;

    /** Tap = normal scramble (click listener). Hold = scramble until the finger lifts. */
    private void installScrambleHold(final Button b) {
        holdScrambleStart = new Runnable() {
            @Override public void run() { startHoldScramble(b); }
        };
        b.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        holdTimerFired = false;
                        v.setPressed(true);
                        handler.postDelayed(holdScrambleStart, HOLD_SCRAMBLE_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        return true;
                    case MotionEvent.ACTION_UP:
                        handler.removeCallbacks(holdScrambleStart);
                        v.setPressed(false);
                        if (holdTimerFired) stopHoldScramble();
                        else v.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(holdScrambleStart);
                        v.setPressed(false);
                        if (holdTimerFired) stopHoldScramble();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void startHoldScramble(View anchor) {
        holdTimerFired = true;
        if (puzzleBusy()) return;
        resetTimerState();
        clearSolutionComparison();
        boolean started;
        if (activeShape == 4) {
            cube.reset();
            started = cube.beginHoldScramble();
        } else {
            polygon.reset();
            started = polygon.beginHoldScramble();
        }
        holdScrambleActive = started;
        if (started) anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        updateAll();
    }

    private void stopHoldScramble() {
        if (!holdScrambleActive) return;
        holdScrambleActive = false;
        if (activeShape == 4) cube.endHoldScramble();
        else polygon.endHoldScramble();
    }

    private void showTutorial() {
        if (activeShape != 4) {
            String instructions = "Pentagon Megaminx: twelve pentagonal faces. "
                + "Use - and + to select 1, 2, or 3 cut rows. "
                + "Touch a sticker and drag: the target turning layer is highlighted. "
                + "Release to commit, or drag back to cancel. If the gesture is ambiguous, "
                + "nothing turns. Drag empty space to orbit; pinch with two fingers to zoom. "
                + "Net shows the live F1-F12 face map. Outer face turns are F1...F12; "
                + "inner independent slice turns are F1L2 and F1L3 where available. "
                + "Reverse with apostrophe, e.g. F2L2'. Hint/Solve reverse the recorded "
                + "move history. Smart Solve calculates a separate solution for Square 2x2/3x3 and the standard Megaminx (1 row). "
                + "Tap Scramble for a normal scramble, or hold it to keep scrambling until you let go.";
            new AlertDialog.Builder(this).setTitle("How to play")
                    .setMessage(instructions).setPositiveButton("Close", null).show();
            return;
        }
        String text = "Beginner method for a 3x3x3 (larger cubes: first group the centres and "
                + "edges, then solve like a 3x3x3).\n\n"
                + "1. White cross: put the four white edges around the white centre, matching "
                + "their side colours.\n"
                + "2. White corners: bring each white corner under its slot and repeat "
                + "R U R' U' until it drops in.\n"
                + "3. Middle layer: slide each non-yellow edge from the top into place with "
                + "U R U' R' U' F' U F (right) or the mirror (left).\n"
                + "4. Yellow cross: repeat F R U R' U' F' until a yellow cross appears.\n"
                + "5. Yellow face: repeat R U R' U R U2 R' until the whole top is yellow.\n"
                + "6. Place corners: cycle them with U R U' L' U R' U' L until each sits in "
                + "the right spot.\n"
                + "7. Place edges: cycle the last three edges with R U' R U R U R U' R' U' R2.\n\n"
                + "Use Hint to see the next moves that undo your scramble, and More > Apply "
                + "moves to type algorithms.";
        new AlertDialog.Builder(this).setTitle("How to solve")
                .setMessage(text).setPositiveButton("Close", null).show();
    }

    private void shareLast() {
        String msg = prefs.getString("lastShare", null);
        if (msg == null) {
            Toast.makeText(this, "No solve to share yet", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, msg);
        startActivity(Intent.createChooser(i, "Share solve"));
    }

    private void showHint() {
        if (puzzleBusy()) return;
        String h = activeShape == 4 ? cube.hint(3) : polygon.hint(3);
        if (h == null) {
            hintText = puzzleSolved() ? "Already solved" : "No hint available";
        } else {
            hintText = "Hint: " + h;
        }
        updateStatus(puzzleSolved(), puzzleMoves());
    }

    private void showSolveOptions() {
        if (puzzleBusy()) return;
        if (puzzleSolved()) {
            Toast.makeText(this, "Already solved", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] choices = {"Smart Solve — calculate from stickers",
                "History Solve — reverse recorded moves"};
        new AlertDialog.Builder(this).setTitle("Choose solving method")
                .setItems(choices, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) startSmartSolve();
                        else autoSolve();
                    }
                }).show();
    }

    /** Smart Solve: state-based searches for 2x2/3x3 squares, layer-order solve for Megaminx. */
    private void startSmartSolve() {
        if (puzzleBusy()) return;
        final boolean megaminx = activeShape != 4 && polygon.getRows() == 1;
        if (!megaminx && (activeShape != 4 || (cube.getSize() != 2 && cube.getSize() != 3))) {
            new AlertDialog.Builder(this).setTitle("Smart Solve not available")
                    .setMessage("Smart Solve supports Square 2x2 and 3x3 and the standard "
                            + "Megaminx (1 row). For larger squares and 2-3 row Megaminx, "
                            + "choose History Solve. That option reverses recorded moves "
                            + "and needs the history.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        final CubeModel state = megaminx ? null : cube.smartSnapshot();
        final int[] megaColors = megaminx ? polygon.smartSnapshot() : null;
        final int generation = ++smartGeneration;
        final SmartCancel token = new SmartCancel();
        smartCancel = token;
        smartBusy = true;
        if (megaminx) polygon.setSmartLocked(true); else cube.setSmartLocked(true);
        hintText = "Smart Solve: calculating from stickers...";
        updateAll();
        smartProgress = new AlertDialog.Builder(this).setTitle("Smart Solve")
                .setMessage("Calculating a solution from the current cube state. "
                        + "The first calculation builds search tables. No history is used.")
                .setNegativeButton("Cancel", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        cancelSmartSolve();
                    }
                }).setCancelable(false).show();
        new Thread(new Runnable() {
            @Override public void run() {
                SmartCubeSolver.Result answer = null;
                String failure = null;
                try {
                    answer = megaminx ? SmartMegaminxSolver.solve(megaColors, token)
                            : SmartCubeSolver.solve(state, token);
                } catch (RuntimeException error) {
                    failure = error.getMessage() == null ? "Search failed" : error.getMessage();
                }
                final SmartCubeSolver.Result result = answer;
                final String problem = failure;
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (generation != smartGeneration || token.cancelled()) return;
                        smartBusy = false;
                        smartCancel = null;
                        cube.setSmartLocked(false);
                        polygon.setSmartLocked(false);
                        if (smartProgress != null) {
                            smartProgress.dismiss();
                            smartProgress = null;
                        }
                        hintText = null;
                        if (result != null && !result.moves.isEmpty()) {
                            assisted = true;
                            if (megaminx ? polygon.playSmartSolution(result.moves)
                                    : cube.playSmartSolution(result.moves)) {
                                recordSolutionComparison("Smart", result.moves.size());
                                Toast.makeText(MainActivity.this,
                                        "Smart Solve: " + result.moves.size()
                                                + (megaminx ? " turns" : " quarter-turns"),
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(MainActivity.this,
                                        "Could not play calculated solution", Toast.LENGTH_LONG).show();
                            }
                        } else if (problem != null) {
                            new AlertDialog.Builder(MainActivity.this).setTitle("Smart Solve failed")
                                    .setMessage(problem + ". Your puzzle was not changed.")
                                    .setPositiveButton("OK", null).show();
                        }
                        updateAll();
                    }
                });
            }
        }, "RubikSmartSolver").start();
    }

    private void cancelSmartSolve() {
        if (smartCancel != null) smartCancel.stopped = true;
        smartCancel = null;
        ++smartGeneration;
        smartBusy = false;
        if (cube != null) cube.setSmartLocked(false);
        if (polygon != null) polygon.setSmartLocked(false);
        if (smartProgress != null) {
            smartProgress.dismiss();
            smartProgress = null;
        }
        hintText = null;
        updateAll();
    }

    private void autoSolve() {
        if (puzzleBusy()) return;
        if (puzzleSolved()) {
            Toast.makeText(this, "Already solved", Toast.LENGTH_SHORT).show();
            return;
        }
        // Capture the move count before History Solve consumes its recorded trail.
        final int steps = activeShape == 4 ? cube.getHistorySolveCount()
                : polygon.getHistorySolveCount();
        if (!(activeShape == 4 ? cube.autoSolve() : polygon.autoSolve())) {
            Toast.makeText(this, "Nothing to play back for this cube", Toast.LENGTH_SHORT).show();
            return;
        }
        assisted = true;
        hintText = null;
        recordSolutionComparison("History", steps);
        updateAll();
    }

    // ------------------------------------------------------------------ selectable puzzle shapes

    private boolean puzzleBusy() {
        return smartBusy || (activeShape == 4 ? cube.isBusy() : polygon.isBusy());
    }

    private boolean puzzleSolved() {
        return activeShape == 4 ? cube.isSolved() : polygon.isSolved();
    }

    private int puzzleMoves() {
        return activeShape == 4 ? cube.getMoves() : polygon.getMoves();
    }

    private int statsId() {
        return activeShape == 4 ? cube.getSize() : (pentagonRows == 1 ? -5 : -5 - 10 * pentagonRows);
    }

    private String comparisonKey() {
        return activeShape == 4 ? "square_" + cube.getSize()
                : "pentagon_" + pentagonRows;
    }

    private void loadSolutionComparison() {
        String key = comparisonKey();
        lastSolutionMoves = prefs.getInt("compareMoves_" + key, -1);
        lastSolutionMode = prefs.getString("compareMode_" + key, "");
    }

    private void recordSolutionComparison(String mode, int moves) {
        lastSolutionMoves = moves;
        lastSolutionMode = mode;
        String key = comparisonKey();
        prefs.edit().putInt("compareMoves_" + key, moves)
                .putString("compareMode_" + key, mode).apply();
        updateComparisonText();
    }

    private void clearSolutionComparison() {
        lastSolutionMoves = -1;
        lastSolutionMode = "";
        String key = comparisonKey();
        prefs.edit().remove("compareMoves_" + key)
                .remove("compareMode_" + key).apply();
        updateComparisonText();
    }

    private void updateComparisonText() {
        if (comparisonText == null || cube == null || polygon == null) return;
        int scramble = activeShape == 4 ? cube.getLastScrambleCount()
                : polygon.getLastScrambleCount();
        StringBuilder sb = new StringBuilder("Scramble: ");
        sb.append(scramble > 0 ? String.valueOf(scramble) : "—");
        sb.append(" turns   |   ");
        if (lastSolutionMoves >= 0 && lastSolutionMode.length() != 0) {
            sb.append(lastSolutionMode).append(" Solve: ")
                    .append(lastSolutionMoves).append(" turns");
        } else {
            sb.append("Your moves: ").append(puzzleMoves());
        }
        comparisonText.setText(sb.toString());
    }

    private void persistActivePuzzle() {
        if (activeShape == 4) {
            prefs.edit().putString("stateSquare", cube.saveState()).apply();
        } else {
            prefs.edit().putString("statePentagonRows" + polygon.getRows(), polygon.saveState())
                    .putInt("pentagonRows", polygon.getRows()).apply();
        }
    }

    private void activateShape(int shape, boolean saveOld) {
        if (saveOld) {
            if (puzzleBusy()) return;
            persistActivePuzzle();
            resetTimerState();
        }
        activeShape = shape;
        cube.setVisibility(shape == 4 ? View.VISIBLE : View.GONE);
        polygon.setVisibility(shape == 4 ? View.GONE : View.VISIBLE);
        if (shape == 5) {
            String state = prefs.getString("statePentagonRows" + pentagonRows,
                    pentagonRows == 1 ? prefs.getString("statePolygon5", null) : null);
            if (state == null || !polygon.restoreState(state)
                    || polygon.getRows() != pentagonRows) {
                polygon.setShape(ShapePuzzleModel.PENTAGON, pentagonRows);
            }
            pentNet.bind(polygon.getModel());
        }
        net.setVisibility(shape == 4 ? View.VISIBLE : View.GONE);
        pentNet.setVisibility(shape == 5 ? View.VISIBLE : View.GONE);
        netHolder.setVisibility(View.GONE);
        prefs.edit().putInt("puzzleShape", shape).apply();
        loadSolutionComparison();
        updateSizeLabel();
        updateAll();
    }

    private void chooseShape() {
        if (puzzleBusy()) {
            Toast.makeText(this, "Finish the current move first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = {"Square - Rubik's Cube", "Pentagon - Megaminx"};
        int selection = activeShape - 4;
        new AlertDialog.Builder(this).setTitle("Puzzle shape")
                .setSingleChoiceItems(names, selection,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface dialog, int which) {
                                int shape = which + 4;
                                if (shape != activeShape) activateShape(shape, true);
                                dialog.dismiss();
                            }
                        }).show();
    }

    // ------------------------------------------------------------------ size & timer

    private void changeSize(int delta) {
        if (puzzleBusy()) return;
        if (activeShape == 5) {
            int newRows = pentagonRows + delta;
            if (newRows < ShapePuzzleModel.MIN_ROWS || newRows > ShapePuzzleModel.MAX_ROWS) return;
            persistActivePuzzle();
            resetTimerState();
            pentagonRows = newRows;
            prefs.edit().putInt("pentagonRows", pentagonRows).apply();
            String state = prefs.getString("statePentagonRows" + pentagonRows,
                    pentagonRows == 1 ? prefs.getString("statePolygon5", null) : null);
            if (state == null || !polygon.restoreState(state)
                    || polygon.getRows() != pentagonRows) {
                polygon.setShape(ShapePuzzleModel.PENTAGON, pentagonRows);
            }
            pentNet.bind(polygon.getModel());
            loadSolutionComparison();
            updateSizeLabel();
            updateAll();
            return;
        }
        int s = cube.getSize() + delta;
        if (s < MIN_SIZE || s > MAX_SIZE) return;
        resetTimerState();
        cube.setSize(s);
        clearSolutionComparison();
        updateSizeLabel();
    }

    private void updateSizeLabel() {
        if (activeShape == 4) {
            int s = cube.getSize();
            sizeLabel.setText(s + "x" + s + "x" + s);
            shapeButton.setText("Square");
        } else {
            sizeLabel.setText(pentagonRows + (pentagonRows == 1 ? " row" : " rows"));
            shapeButton.setText("Pentagon");
        }
        minusButton.setEnabled(activeShape == 4 ? cube.getSize() > MIN_SIZE
                : pentagonRows > ShapePuzzleModel.MIN_ROWS);
        plusButton.setEnabled(activeShape == 4 ? cube.getSize() < MAX_SIZE
                : pentagonRows < ShapePuzzleModel.MAX_ROWS);
        netButton.setEnabled(true);
    }

    private void resetTimerState() {
        phase = IDLE;
        assisted = false;
        doneMessage = null;
        hintText = null;
        elapsedMs = 0;
        handler.removeCallbacks(tick);
    }

    private void updateAll() {
        updateStatus(puzzleSolved(), puzzleMoves());
        updateComparisonText();
        undoBtn.setEnabled(activeShape == 4 ? cube.canUndo() : polygon.canUndo());
        redoBtn.setEnabled(activeShape == 4 ? cube.canRedo() : polygon.canRedo());
        net.invalidate();
        pentNet.invalidate();
    }

    private void updateTimerText() {
        switch (phase) {
            case RUNNING:
                timerText.setTextColor(Color.WHITE);
                handler.removeCallbacks(tick);
                handler.post(tick);
                break;
            case DONE:
                timerText.setTextColor(0xFF4CAF50);
                timerText.setText(Stats.formatTime(elapsedMs));
                break;
            case ARMED:
                timerText.setTextColor(0xFFB0B0C0);
                timerText.setText("0:00.0");
                break;
            default:
                timerText.setTextColor(0xFFB0B0C0);
                timerText.setText("-:--.-");
                break;
        }
    }

    private void updateStatus(boolean solved, int moves) {
        updateTimerText();
        updateComparisonText();
        if (hintText != null) {
            status.setTextColor(0xFFFFD500);
            status.setText(hintText);
        } else if (phase == DONE && doneMessage != null) {
            status.setTextColor(0xFF4CAF50);
            status.setText(doneMessage);
        } else if (solved && moves == 0) {
            status.setTextColor(Color.WHITE);
            status.setText(activeShape == 4
                    ? "Drag a sticker: turn layer   Drag background: orbit"
                    : "Drag: preview face, release: turn   Pinch: zoom");
        } else if (solved) {
            status.setTextColor(0xFF4CAF50);
            status.setText("Solved in " + moves + " moves!");
        } else {
            status.setTextColor(Color.WHITE);
            String last = activeShape == 4 ? cube.lastMovesNotation(6)
                    : polygon.lastMovesNotation(6);
            status.setText("Moves: " + moves + (last.length() > 0 ? "   " + last : ""));
        }
    }

    // ------------------------------------------------------------------ CubeView.Listener

    @Override
    public void onCubeChanged(boolean solved, int moves) {
        hintText = null;
        if (phase == DONE && !solved) {
            phase = IDLE;
            doneMessage = null;
        }
        if (solved && phase == RUNNING) {
            finishSolve(moves);
        } else if (solved && phase == ARMED) {
            phase = IDLE;
        }
        updateAll();
    }

    @Override
    public void onUserMove() {
        if (phase == ARMED) {
            phase = RUNNING;
            startMs = System.currentTimeMillis();
            elapsedMs = 0;
            handler.removeCallbacks(tick);
            handler.post(tick);
        }
    }

    @Override
    public void onScrambleFinished() {
        phase = ARMED;
        assisted = false;
        elapsedMs = 0;
        updateAll();
    }

    private void finishSolve(int moves) {
        elapsedMs = System.currentTimeMillis() - startMs;
        phase = DONE;
        handler.removeCallbacks(tick);
        int size = statsId();
        String time = Stats.formatTime(elapsedMs);
        if (assisted) {
            doneMessage = "Auto-solved (not counted in statistics)";
        } else {
            recordSolutionComparison("Player", moves);
            int best = stats.add(size, elapsedMs, moves);
            StringBuilder sb = new StringBuilder("Solved in " + moves + " moves!");
            if ((best & 1) != 0) sb.append(" New best time!");
            else if ((best & 2) != 0) sb.append(" Fewest moves yet!");
            doneMessage = sb.toString();
            String puzzleName = activeShape == 4
                    ? cube.getSize() + "x" + cube.getSize() + "x" + cube.getSize() + " Rubik's cube"
                    : "pentagonal Megaminx (" + pentagonRows + " rows)";
            prefs.edit().putString("lastShare", "I solved a " + puzzleName
                    + " in " + time + " with " + moves + " moves!").apply();
            celebrate();
        }
    }

    private void celebrate() {
        if (setHaptics) {
            cube.performHapticFeedback(Build.VERSION.SDK_INT >= 30
                    ? 16 /* CONFIRM */ : HapticFeedbackConstants.LONG_PRESS);
        }
        if (setSound) {
            try {
                final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 60);
                tg.startTone(ToneGenerator.TONE_PROP_ACK, 250);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        tg.release();
                    }
                }, 500);
            } catch (RuntimeException ignored) {
                // no audio available
            }
        }
    }
}
