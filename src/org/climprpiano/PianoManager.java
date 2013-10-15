package org.climprpiano;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import org.climprpiano.midisheetmusic.FileUri;
import org.climprpiano.midisheetmusic.MidiFile;
import org.climprpiano.midisheetmusic.MidiFileException;
import org.climprpiano.midisheetmusic.MidiNote;
import org.climprpiano.midisheetmusic.MidiTrack;
import org.hexiano.SoundController;
import org.json.JSONArray;
import org.json.JSONObject;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;

@SuppressLint("UseSparseArrays")
public class PianoManager {

	enum PlayMode {
		PLAY_ALONG, FOLLOW_YOU, RYTHM_TAP, LISTEN
	}

	enum HandMode {
		LEFT, RIGHT, BOTH
	}

	enum PlayState {
		STOP, PLAY, PAUSE, WAIT
	}

	enum RepeatMode {
		NONE, ALL, SINGLE
	}

	private PianoRollView pianoRollView;
	private PianoKeyboardView pianoKeyboardView;
	private PianoActivity pianoActivity;

	private PlayMode playMode;
	private HandMode handMode;
	private RepeatMode repeatMode;

	private PlayState playState;
	private double currentPulseTime;
	private double playSpeed;

	Handler timer;
	/** Timer used to update the sheet music while playing */
	long startTime;
	/** Absolute time when music started playing (msec) */
	double startPulseTime;
	/** Time (in pulses) when music started playing */
	double pulsesPerMsec;
	/** The number of pulses per millisec */

	private List<Double> loopMarks;

	private FileUri fileUri;
	private MidiFile midifile;

	private SoundController soundController; // the hexiano component for playing notes

	public PianoManager(PianoRollView pianoRollView, PianoKeyboardView pianoKeyboardView, PianoActivity pianoActivity) {
		this.pianoRollView = pianoRollView;
		this.pianoKeyboardView = pianoKeyboardView;
		this.pianoActivity = pianoActivity;

		initOptions();

		timer = new Handler();

		soundController = new SoundController(this.pianoActivity.getApplicationContext());
	}

	private void initOptions() {
		setPlayMode(PlayMode.FOLLOW_YOU);
		setHandMode(HandMode.RIGHT);
		setRepeatMode(RepeatMode.NONE);
		setPlayState(PlayState.STOP);
		setCurrentPulseTime(0);
		setPlaySpeed(1);
		loopMarks = new Vector<Double>();

		pianoRollView.update();
		pianoKeyboardView.shadeKeys(new HashMap<Integer, Integer>());
	}

	/**
	 * sets currentPulseTime to the previous loop mark or the beginning of the song
	 */
	public void loopBackward() {
		if (loopMarks.size() == 0) {
			setCurrentPulseTime(0);
			return;
		}
		Collections.sort(loopMarks);
		// start the search 100 msec before the current pulse
		int position = Collections.binarySearch(loopMarks, getCurrentPulseTime() - 100 * pulsesPerMsec);
		if (position < 0) {
			position = -position - 2;
			if (position < 0) {
				setCurrentPulseTime(0);
				return;
			}
		}
		setCurrentPulseTime(loopMarks.get(position));
	}

	/**
	 * sets currentPulseTime to the next loop mark or the beginning of the song
	 */
	public void loopForward() {
		if (loopMarks.size() == 0) {
			if (midifile != null) {
				setCurrentPulseTime(midifile.getTotalPulses());
			}
			return;
		}
		Collections.sort(loopMarks);
		// start the search 100 msec after the current pulse
		int position = Collections.binarySearch(loopMarks, getCurrentPulseTime() + 100 * pulsesPerMsec);
		if (position < 0) {
			position = -position - 1;
			if (position > loopMarks.size() - 1) {
				if (midifile != null) {
					setCurrentPulseTime(midifile.getTotalPulses());
				}
				return;
			}
		}
		setCurrentPulseTime(loopMarks.get(position));
	}

	Map<Integer, Integer> keyColors;

	public void pianoKeyPress(int midiNote, int velocity) {
		// TODO
		if (keyColors == null) {
			keyColors = new HashMap<Integer, Integer>();
		}
		if (velocity > 0 && !keyColors.containsKey(midiNote)) {
			keyColors.put(midiNote, Color.GREEN);
		} else if (velocity == -1 && keyColors.containsKey(midiNote)) {
			keyColors.remove(midiNote);
		}
		pianoKeyboardView.shadeKeys(keyColors);
	}

	/**
	 * The callback for the timer. If the midi is still playing, update the currentPulseTime and shade the sheet music. If a stop or pause has been initiated
	 * (by someone clicking the stop or pause button), then stop the timer.
	 */
	Runnable TimerCallback = new Runnable() {
		public void run() {
			if (midifile == null || pianoRollView == null) {
				setPlayState(PlayState.STOP);
				return;
			} else if (playState == PlayState.STOP) {
				return;
			} else if (playState == PlayState.PAUSE) {
				Log.d("play", "pause");
				return;
			} else if (playState == PlayState.PLAY) {
				long msec = SystemClock.uptimeMillis() - startTime;
				currentPulseTime = startPulseTime + msec * pulsesPerMsec;

				/* Stop if we've reached the end of the song */
				if (currentPulseTime > midifile.getTotalPulses()) {
					setPlayState(PlayState.STOP);
					return;
				}

				// calculate the colors for the keyboard keys
				Map<Integer, Integer> keyColors = new HashMap<Integer, Integer>();
				// and the notes to play via audio
				Map<Integer, Integer> notes = new HashMap<Integer, Integer>();
				for (MidiTrack track : midifile.getTracks()) {
					for (MidiNote note : track.getNotes()) {
						if (note.getStartTime() < currentPulseTime
						        && note.getStartTime() + note.getDuration() > currentPulseTime) {
							keyColors.put(note.getNumber(), Color.RED);
							notes.put(note.getNumber(), 127);
						}
					}
				}
				pianoKeyboardView.shadeKeys(keyColors);
				pianoRollView.update();
				soundController.setNotes(notes);

				timer.postDelayed(TimerCallback, 20);
				return;
			}
		}
	};

	/**
	 * @return the pianoRollView
	 */
	public PianoRollView getPianoRollView() {
		return pianoRollView;
	}

	/**
	 * @return the pianoKeyboardView
	 */
	public PianoKeyboardView getPianoKeyboardView() {
		return pianoKeyboardView;
	}

	/**
	 * @return the playMode
	 */
	public PlayMode getPlayMode() {
		return playMode;
	}

	/**
	 * @param playMode
	 *            the playMode to set
	 */
	public void setPlayMode(PlayMode playMode) {
		this.playMode = playMode;
		this.pianoActivity.setPlayMode(playMode);
	}

	/**
	 * @return the handMode
	 */
	public HandMode getHandMode() {
		return handMode;
	}

	/**
	 * @param handMode
	 *            the handMode to set
	 */
	public void setHandMode(HandMode handMode) {
		this.handMode = handMode;
		this.pianoActivity.setHandMode(handMode);
	}

	/**
	 * @return the playStatus
	 */
	public PlayState getPlayState() {
		return playState;
	}

	/**
	 * @param playState
	 *            the playStatus to set
	 */
	public void setPlayState(PlayState playState) {
		if (playState != this.playState) {
			switch (playState) {
			case PLAY:
				pianoActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

				timer.removeCallbacks(TimerCallback);
				timer.postDelayed(new Runnable() {
					public void run() {
						startTime = SystemClock.uptimeMillis();

						timer.removeCallbacks(TimerCallback);
						timer.postDelayed(TimerCallback, 100);

						return;
					}
				}, 1000);
				break;
			case PAUSE:
				Log.d("manager", "pause");
				pianoActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				break;
			case STOP:
				pianoActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				break;
			default:
				break;
			}
			this.playState = playState;
		}
	}

	/**
	 * @return the repeatMode
	 */
	public RepeatMode getRepeatMode() {
		return repeatMode;
	}

	/**
	 * @param repeatMode
	 *            the repeatMode to set
	 */
	public void setRepeatMode(RepeatMode repeatMode) {
		this.repeatMode = repeatMode;
		this.pianoActivity.setRepeatMode(repeatMode);
	}

	/**
	 * @return the playSpeed
	 */
	public double getPlaySpeed() {
		return playSpeed;
	}

	/**
	 * @param playSpeed
	 *            the playSpeed to set
	 */
	public void setPlaySpeed(double playSpeed) {
		this.playSpeed = playSpeed;
		this.pianoActivity.setPlaySpeed(playSpeed);

		if (midifile != null) {
			double inverse_tempo = 1.0 / midifile.getTime().getTempo();
			double inverse_tempo_scaled = inverse_tempo * playSpeed;
			pulsesPerMsec = midifile.getTime().getQuarter() * (1000.0 * inverse_tempo_scaled);
		}
	}

	/**
	 * @return the currentPulseTime
	 */
	public double getCurrentPulseTime() {
		return currentPulseTime;
	}

	/**
	 * @param currentPulseTime
	 *            the currentPulseTime to set
	 */
	public void setCurrentPulseTime(double currentPulseTime) {
		if (midifile != null) {
			this.currentPulseTime = Math.max(0, Math.min(currentPulseTime, midifile.getTotalPulses()));
			if (pianoRollView != null) {
				pianoRollView.update();
			}
		}
	}

	/**
	 * @return the fileUri
	 */
	public FileUri getFileUri() {
		return fileUri;
	}

	/**
	 * @param fileUri
	 *            the fileUri to set
	 */
	public void setFileUri(FileUri fileUri) {
		if (this.fileUri != null && this.fileUri.equals(fileUri)) {
			return;
		}

		this.fileUri = fileUri;

		byte[] data;
		try {
			data = fileUri.getData(pianoActivity);
			midifile = new MidiFile(data, fileUri.getUri().getLastPathSegment());
			initOptions();
		} catch (MidiFileException e) {
		}

		updateRecentFile(pianoActivity, fileUri);

		pianoActivity.updateLatestSongs(fileUri);
	}

	/**
	 * Save the given FileUri into the "recentFiles" preferences. Save a maximum of 10 recent files.
	 */
	public static void updateRecentFile(Context context, FileUri recentfile) {
		try {
			SharedPreferences settings = context.getSharedPreferences("climprpiano.recentFiles", 0);
			SharedPreferences.Editor editor = settings.edit();
			JSONArray prevRecentFiles = null;
			String recentFilesString = settings.getString("recentFiles", null);
			if (recentFilesString != null) {
				prevRecentFiles = new JSONArray(recentFilesString);
			} else {
				prevRecentFiles = new JSONArray();
			}
			JSONArray recentFiles = new JSONArray();
			JSONObject recentFileJson = recentfile.toJson();
			recentFiles.put(recentFileJson);
			// only store 10 most recent files
			for (int i = 0; i < Math.min(10, prevRecentFiles.length()); i++) {
				JSONObject file = prevRecentFiles.getJSONObject(i);
				if (!FileUri.equalJson(recentFileJson, file)) {
					recentFiles.put(file);
				}
			}
			editor.putString("recentFiles", recentFiles.toString());
			editor.commit();
		} catch (Exception e) {
		}
	}

	/**
	 * @return the loopMarks
	 */
	public List<Double> getLoopMarks() {
		return loopMarks;
	}

	public void addLoopMark(double loopMark) {
		loopMarks.add(loopMark);
		pianoRollView.update();
	}

	public void removeLoopMark(double loopMark) {
		loopMarks.remove(loopMark);
		pianoRollView.update();
	}

	/**
	 * @return the midifile
	 */
	public MidiFile getMidifile() {
		return midifile;
	}
}