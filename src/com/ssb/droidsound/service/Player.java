package com.ssb.droidsound.service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Message;

import com.ssb.droidsound.SongFile;
import com.ssb.droidsound.file.FileSource;
import com.ssb.droidsound.plugins.DroidSoundPlugin;
import com.ssb.droidsound.utils.Log;


/*
 * The Player thread 
 */
public class Player implements Runnable
{
	private static final String TAG = "Player";
	
	private int frequency = 44100;
	private int channels = 2;
	
	public enum State
	{
		STOPPED, PAUSED, PLAYING, SWITCHING
	};

	public enum CommandName
	{
		NO_COMMAND,
		STOP, // Unload and stop if playing
		PLAY, // Play new file or last file
		DUMP_WAV,
		SET_LOOPMODE,
		RESTART,
		// Only when Playing:
		PAUSE, UNPAUSE, SET_POS, SET_TUNE,
	};
	
	private static class Command
	{
		public Command(CommandName cmd) { command = cmd; }
		public Command(CommandName cmd, Object arg) { command = cmd; argument = arg; }
		public Command(CommandName cmd, int arg) { command = cmd; argument = (Integer)arg; }
		public CommandName command;
		public Object argument;
	}

	public static final int MSG_NEWSONG = 0;
	public static final int MSG_DONE = 2;
	public static final int MSG_STATE = 1;
	public static final int MSG_PROGRESS = 3;
	public static final int MSG_SILENT = 4;
	public static final int MSG_SUBTUNE = 5;
	public static final int MSG_DETAILS = 6;
	public static final int MSG_WAVDUMPED = 7;
	public static final int MSG_RESTART = 8;
	public static final int MSG_FAILED = 99;

	private Handler mHandler;
	
	private AsyncAudioPlayer audioPlayer;
	private MediaPlayer mp;
	private int dataSize;

	private DroidSoundPlugin currentPlugin;
	private List<DroidSoundPlugin> plugins;
	
	private Object cmdLock = new Object();
	private LinkedList<Command> commands = new LinkedList<Command>();

	private  byte [] [] binaryInfo = new byte [1] [];

	private int noPlayWait;
	private int lastPos;
	private ConcurrentHashMap<String, Object> songDetails = new ConcurrentHashMap<String, Object>();
	private int currentTune;
	private volatile State currentState = State.STOPPED;
	private boolean songEnded = false;
	private boolean startedFromSub;
	private int lastLatency;
	private boolean firstData;
	private volatile int loopMode = 0;
	private volatile int oldLoopMode = -1;
	private Thread audioThread;
	private boolean isSilent;
	private SongFile lastSong;

	public Player(AudioManager am, Handler handler, Context ctx)
	{
		mHandler = handler;
		plugins = DroidSoundPlugin.createPluginList();
		dataSize = 44100;
	}


	@SuppressLint("DefaultLocale")
	public static String makeSize(long fileSize)
	{
		String s;
		if(fileSize < 10 * 1024) {
			s = String.format("%1.1fKB", (float) fileSize / 1024F);
		} else if(fileSize < 1024 * 1024) {
			s = String.format("%dKB", fileSize / 1024);
		} else if(fileSize < 10 * 1024 * 1024) {
			s = String.format("%1.1fMB", (float) fileSize / (1024F * 1024F));
		} else {
			s = String.format("%dMB", fileSize / (1024 * 1024));
		}
		return s;
	}

	private void dumpWav(SongFile song, File outFile, int length, int flags) throws IOException
	{
		
		int FREQ = 44100;

		Log.d(TAG, "IN DUMP WAV");
		
		startSong(song, true);
		
		audioPlayer.stop();
		
		int bufferSize = 1024*64;

		byte [] bbuffer = new byte [bufferSize * 8];
		
		int numSamples = (int)(((long)length * FREQ) / 1000);

		int numChannels = 1;
		int divider = 2;
		
		
		if((flags & 2) != 0) {
			Log.d(TAG, "HIGH QUALITY!");
			numChannels = 2;
			divider = 1;
		}

		int wavSamples = numSamples / divider;
		
		// the /2 halves the frequency
		
		int freq = FREQ/divider;
		
		// Size in bytes of wavdata
		int dataSize = wavSamples * numChannels * 16/8;
		
		Log.d(TAG, "%dsec => %d samples, %d wavSamples, %d dataSize", length/1000, numSamples, wavSamples, dataSize);

		
		byte [] barray = new byte [128];
		ByteBuffer bb = ByteBuffer.wrap(barray);
		bb.order(ByteOrder.LITTLE_ENDIAN);
		bb.put(new byte[] { 'R', 'I', 'F', 'F' });
		bb.putInt(36 + dataSize);
		bb.put(new byte[] { 'W', 'A', 'V', 'E' });
		bb.put(new byte[] { 'f', 'm', 't', ' ' });
		bb.putInt(16);
		bb.putShort((short) 1);
		bb.putShort((short) numChannels);
		bb.putInt(freq);
		bb.putInt(freq * numChannels * 16/8);
		bb.putShort((short) (numChannels * 16/8));
		bb.putShort((short) 16);

		bb.put(new byte[] { 'd', 'a', 't', 'a' });
		bb.putInt(dataSize);
		
		//bos.write(barray, 0, bb.position());
			
		RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
		raf.write(barray, 0, bb.position());
		
		short [] samples = new short [dataSize];
		
		int sampleCount = 0;
		int bytesWritten = 0;
		while(sampleCount < numSamples) {
			// In and out are number of shorts, not number of samples (2 bytes vs 4 bytes in 16bit stereo)
			int len = currentPlugin.getSoundData(samples, bufferSize);
			Log.d(TAG, "READ %d (%d)", len, bufferSize);
			if(len < 0) {
				wavSamples = sampleCount / divider;
				dataSize = wavSamples * numChannels * 16/8;
				
				Log.d(TAG, "Early end, new datasize is %d", dataSize);
				
				bb.putInt(4, dataSize + 36);
				bb.putInt(40, dataSize);
				raf.seek(0);
				raf.write(barray, 0, bb.position());
				break;
			}
			
			len /= 2; // len now samples, not shorts
			
			if(sampleCount + len > numSamples)
				len = numSamples - sampleCount; 
			
			sampleCount += len;
			
			Log.d(TAG, "%d vs %d frames", numSamples, sampleCount);
			
			int j = 0;
			int i = 0;			
			int volume = 100;
			// Now we iterate over sample values which are twice as many
			while(i < len*2) {
				
				if((flags & 2) != 0) {
					bbuffer[j++] = (byte) (samples[i]&0xff);
					bbuffer[j++] = (byte) ((samples[i]>>8)&0xff);
					i += 1;
				} else {
					int avg = ((int)samples[i] + (int)samples[i+1] + (int)samples[i+2] + (int)samples[i+3]);
					short savg = (short) (avg * volume / 400);								
					i += 4;
					bbuffer[j++] = (byte) (savg&0xff);
					bbuffer[j++] = (byte) ((savg>>8)&0xff);
				}
			}			
			raf.write(bbuffer, 0, j);	
			bytesWritten += j;
		}
		raf.close();
		
		Log.d(TAG, "Wrote %d samples in %d bytes", numSamples, bytesWritten);

		currentPlugin.unload();		
		currentPlugin = null;
		currentState = State.STOPPED;
		
		Message msg = mHandler.obtainMessage(MSG_WAVDUMPED, outFile.getPath());
		mHandler.sendMessage(msg);
	}
	
	
	private FileSource openSong(String path)
	{
		
		FileSource songSource = null;
		
		if(path.startsWith("http"))
			songSource = FileSource.create(path);
		else if(path.startsWith("ftp"))
			songSource = FileSource.create(path);

		else
		if(lastSong.getZipPath() != null && !lastSong.exists())
		{
			songSource = FileSource.create(lastSong.getZipPath() + '/' + lastSong.getZipName()); 
		} 
		else
		{
			songSource = FileSource.fromFile(lastSong.getFile());
		}
		
		return songSource;
		
	}

	
	private void reloadSong()
	{
		currentPlugin.unload();
		String path = lastSong.getPath();
		FileSource songSource = openSong(path);
		if(songSource == null)
		{
			Message msg = mHandler.obtainMessage(MSG_FAILED, "Failed to open " + path);
			mHandler.sendMessage(msg);
			return;
		}
	
		currentPlugin.load(songSource);
		songSource.close();
		songSource = null;
				
		if(currentTune > 0)
		{
			currentPlugin.setTune(currentTune);
		}
	}
	
	private void createNewAudioPlayer(int buffer_size, int frequency, int channels)
	{
		audioPlayer = new AsyncAudioPlayer(buffer_size, frequency, channels);
		audioThread = new Thread(audioPlayer, "AudioPlayer");
		audioThread.setPriority(Thread.MAX_PRIORITY);
		audioThread.start();
	}
	
	private void startSong(SongFile song, boolean skipStart)
	{
		Message msg = null;
		
		if(currentPlugin != null)
		{
			currentPlugin.unload();
		}

		currentPlugin = null;
		songEnded = false;
		currentState = State.SWITCHING;
		lastSong = song;
		
		String path = song.getPath();
		
		FileSource songSource = openSong(path);		
		if(songSource == null)
		{
			msg = mHandler.obtainMessage(MSG_FAILED, "Failed to open " + path);
			mHandler.sendMessage(msg);
			return;
		}

		List<DroidSoundPlugin> list = new ArrayList<DroidSoundPlugin>();

		for(DroidSoundPlugin plugin : plugins)
		{
			if(plugin.canHandle(songSource))
			{
				list.add(plugin);
				Log.d(TAG, "%s handled by %s", song.getName(), plugin.getClass().getSimpleName());
			}
		}


		for(DroidSoundPlugin plugin : list)
		{
			Log.d(TAG, "Trying " + plugin.getClass().getName());				
			if(plugin.load(songSource))
			{
				currentPlugin = plugin;
				break;
			}
		}
		
		long size = songSource.getLength();
		songSource.close();
		songSource = null;
		
		songDetails.clear();
				
		if(currentPlugin != null)
		{
						
			
			synchronized (this)
			{
				songDetails.put(SongMeta.FILENAME, song.getPath());

				if(song.getTitle() != null)
				{
					songDetails.put(SongMeta.TITLE, song.getTitle());
				} 
				else
				{
					songDetails.put(SongMeta.TITLE, getPluginInfo(DroidSoundPlugin.INFO_TITLE));
				}

				if(song.getComposer() != null)
				{
					songDetails.put(SongMeta.COMPOSER, song.getComposer());
				} 
				else
				{
					songDetails.put(SongMeta.COMPOSER, getPluginInfo(DroidSoundPlugin.INFO_AUTHOR));
				}

				firstData = false;
				
				if(songDetails.get(SongMeta.TITLE) == null || songDetails.get(SongMeta.TITLE).equals(""))
				{
					if(currentPlugin.delayedInfo())
					{
						songDetails.remove(SongMeta.TITLE);
						firstData = true;
					} 
					else
					{					
						String basename = currentPlugin.getBaseName((String) songDetails.get(SongMeta.FILENAME));
						int sep = basename.indexOf(" - ");
						if(sep > 0)
						{
							songDetails.put(SongMeta.COMPOSER, basename.substring(0, sep));
							songDetails.put(SongMeta.TITLE, basename.substring(sep+3));
						} 
						else 
							songDetails.put(SongMeta.TITLE, basename);
					}
				}
				
				byte [] data = currentPlugin.getBinaryInfo(0);
				if(data != null)
				{
					synchronized (binaryInfo)
					{
						binaryInfo[0] = data;
					}
					songDetails.put("binary", 0);
				}
	
				songDetails.put(SongMeta.COPYRIGHT,  getPluginInfo(DroidSoundPlugin.INFO_COPYRIGHT));
				songDetails.put(SongMeta.FORMAT,  getPluginInfo(DroidSoundPlugin.INFO_TYPE));
				int count = currentPlugin.getIntInfo(DroidSoundPlugin.INFO_SUBTUNE_COUNT);
				if(count == -1) count = 0;
				songDetails.put(SongMeta.TOTAL_SUBTUNES, count);
				songDetails.put(SongMeta.SUBTUNE, currentPlugin.getIntInfo(DroidSoundPlugin.INFO_STARTTUNE));
								
				currentPlugin.getDetailedInfo(songDetails);
				songDetails.put(SongMeta.SIZE, size);
				songDetails.put(SongMeta.CAN_SEEK, currentPlugin.canSeek());
				songDetails.put(SongMeta.POSITION, 0);
				songDetails.put(SongMeta.LENGTH, currentPlugin.getIntInfo(DroidSoundPlugin.INFO_LENGTH));
				songDetails.put(SongMeta.ENDLESS, currentPlugin.isEndless());
				songDetails.put(SongMeta.SUBTUNE_TITLE,  getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_TITLE));
				songDetails.put(SongMeta.SUBTUNE_COMPOSER, getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_AUTHOR));
				
				//currentPlugin.setOption("loop", loopMode);
				
			}

			// Songs in playlists can start from a specific subtune.
			startedFromSub = false;
			currentTune = 0;
			if(song.getSubtune() >= 0)
			{
				songDetails.put(SongMeta.START_SUBTUNE, song.getSubtune());				
				songDetails.put(SongMeta.TOTAL_SUBTUNES, 0);
				
				currentPlugin.setTune(song.getSubtune());
				currentTune = song.getSubtune();
				
				songDetails.put(SongMeta.SUBTUNE, currentTune);
				songDetails.put(SongMeta.POSITION, 0);
				songDetails.put(SongMeta.LENGTH, currentPlugin.getIntInfo(DroidSoundPlugin.INFO_LENGTH));
				songDetails.put(SongMeta.SUBTUNE_TITLE,  getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_TITLE));
				songDetails.put(SongMeta.SUBTUNE_COMPOSER, getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_AUTHOR));
				startedFromSub = true;
			}

			lastLatency = 0;
			songDetails.put(SongMeta.SOURCE, "");
			
			songDetails.put(SongMeta.STATE, 1);
			msg = mHandler.obtainMessage(MSG_NEWSONG, songDetails);
			
			if(!skipStart)
			{					
				
				// Mediaplayer is for MP3 only
				mp = currentPlugin.getMediaPlayer();

				if(mp != null)
				{
					currentState = State.PLAYING;
					mp.setLooping(loopMode == 1);
					lastPos = -1000;
					
					String source = currentPlugin.getStringInfo(102);
					
					if(source != null)
					{
						songDetails.put(SongMeta.SOURCE, source);
					}
					
					mHandler.sendMessage(msg);
					mp.start();
					return;
				}

				else
				{
					// create audioPlayer instead of MediaPlayer
										
					frequency = currentPlugin.getIntInfo(DroidSoundPlugin.INFO_FREQUENCY);
					channels = currentPlugin.getIntInfo(DroidSoundPlugin.INFO_CHANNELS);
					
					if (channels < 1)
					{
						channels = 2;
					}
					
					if (frequency <= 0)
					{
						frequency = 44100;
					}
					
					dataSize = frequency;
											
					createNewAudioPlayer(dataSize, frequency, channels);
					audioPlayer.stop();
					Log.d(TAG, "FOUND PLUGIN:" + currentPlugin.getClass().getName());
					Log.d(TAG, "'%s' by '%s'", song.getTitle(), song.getComposer());
					
					lastPos = -1000;
					mHandler.sendMessage(msg);
					
					currentState = State.PLAYING;
					
					audioPlayer.start();
				}
			}
			return;
		}
		msg = mHandler.obtainMessage(MSG_FAILED);
		mHandler.sendMessage(msg);
		currentState = State.STOPPED;
	}


	private String getPluginInfo(int info)
	{
		String s = currentPlugin.getStringInfo(info);
		if(s == null)
		{
			s = "";
		}
		return s;
	}

	@Override
	public void run()
	{
		currentPlugin = null;
	
		noPlayWait = 0;

		while(noPlayWait < 50) {
			
			if(loopMode != oldLoopMode)
			{
				if(currentPlugin != null)
				{
					currentPlugin.setOption("loop", loopMode);
				}
					
				oldLoopMode = loopMode;
				
				mp = currentPlugin == null ? null : currentPlugin.getMediaPlayer();
				if(mp != null)
				{
					mp.setLooping(loopMode == 1);
				}
					
				songDetails.put(SongMeta.REPEAT, loopMode == 1 ? true : false);				
				Message msg = mHandler.obtainMessage(MSG_DETAILS, songDetails);
				mHandler.sendMessage(msg);
			}
			
			try
			{
				if(commands.size() > 0)
				{
					synchronized (cmdLock) {
						
						Command cmd = commands.removeFirst();
						CommandName command = cmd.command;
						Object argument = cmd.argument;
						Log.d(TAG, "Command %s while in state %s", command.toString(), currentState.toString());

						switch(command)
						{
							
							case PLAY:
								
								if (currentState == State.PLAYING)
								{
									if (mp != null)
									{
										mp.stop();
										if(currentPlugin != null)
										{
											currentPlugin.unload();
											currentPlugin = null;
										}
									}
									
									else
									{
										audioPlayer.stop();
										audioPlayer.exit();
									}
								}
								
								SongFile song = (SongFile) argument;
								Log.d(TAG, "Playmod " + song.getName());
								startSong(song, false);
								if (currentState == State.STOPPED)
								{
									audioPlayer.stop();
								}
								if (currentState != State.STOPPED)
								{
									currentState = State.PLAYING;
								}
								break;
							
							case DUMP_WAV:
								
								Object [] args = (Object []) argument;
								SongFile song2 = (SongFile) args[0];
								File outFile = (File) args[1];
								int len = (Integer) args[2];
								int flags = (Integer) args[3];
								Log.d(TAG, "DUMP WAV " + song2.getName());
								
								try
								{
									dumpWav(song2, outFile, len, flags);
								} 
								catch (IOException e1)
								{
									e1.printStackTrace();
								}
								break;
								
						case STOP:
							if(currentState != State.STOPPED)
							{
								Log.d(TAG, "STOP");
								
								if(mp != null)
								{
									mp.stop();
								} 
								else
								{
									audioPlayer.stop();
								}
								

								if(currentPlugin != null)
								{
									currentPlugin.unload();
									currentPlugin = null;
								}
								currentState = State.STOPPED;
								songDetails.clear();
								songDetails.put(SongMeta.STATE, 0);
								songDetails.put(SongMeta.POSITION, 0);
								Message msg = mHandler.obtainMessage(MSG_STATE, 0, 0);
								mHandler.sendMessage(msg);
							}
							break;
							
						case RESTART:
							if(!currentPlugin.restart())
								if(!currentPlugin.setTune(currentTune)) {
									reloadSong();
								}
							songEnded = false;
							audioPlayer.seekTo(-1);
							Message msg = mHandler.obtainMessage(MSG_RESTART, null);
							mHandler.sendMessage(msg);
							if(currentState == State.SWITCHING)
							{
								currentState = State.PLAYING;
							}							
							break;
						default:
							break;
						}
						if(currentState != State.STOPPED)
						{
							switch(command)
							{
							case SET_POS:
								int msec = (Integer) argument;
								if(currentState == State.SWITCHING)
								{
									currentState = State.PLAYING;
								}
								if(currentPlugin.seekTo(msec))
								{
									if (mp == null)
									{
										audioPlayer.seekTo(msec);
										lastPos = -1000;
									}
									
								}
								break;
							case SET_TUNE:
								Log.d(TAG, "Setting tune");
								if(currentPlugin.setTune((Integer) argument))
								{
									songEnded = false;
									currentTune = (Integer) argument;

									lastPos = -1000;
									audioPlayer.stop();

									if(currentState == State.SWITCHING)
									{
										currentState = State.PLAYING;
									}

									songDetails.put(SongMeta.SUBTUNE, currentTune);
									songDetails.put(SongMeta.POSITION, 0);
									songDetails.put(SongMeta.LENGTH, currentPlugin.getIntInfo(DroidSoundPlugin.INFO_LENGTH));
									songDetails.put(SongMeta.SUBTUNE_TITLE,  getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_TITLE));
									songDetails.put(SongMeta.SUBTUNE_COMPOSER, getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_AUTHOR));

									Message msg = mHandler.obtainMessage(MSG_SUBTUNE, songDetails);
									mHandler.sendMessage(msg);
									audioPlayer.start();
								}
								break;
							case PAUSE:
								if(currentState == State.PLAYING) {
									currentState = State.PAUSED;
									songDetails.put(SongMeta.STATE, 2);
									Message msg = mHandler.obtainMessage(MSG_STATE, 2, 0);
									mHandler.sendMessage(msg);
									
									if(mp != null)
										mp.pause();
									else
										audioPlayer.pause();
								}

								break;
							case UNPAUSE:
								if(currentState == State.PAUSED) {
									currentState = State.PLAYING;
									songDetails.put(SongMeta.STATE, 1);
									Message msg = mHandler.obtainMessage(MSG_STATE, 1, 0);
									mHandler.sendMessage(msg);
									
									if(mp != null)
										mp.start();
									else
										audioPlayer.play();
								}

								break;
							default:
								break;
							}
						}
					}
				}
				
				if(currentState == State.PLAYING)
				{
					if(mp != null)
					{						
						updateMediaPlayer();
					} 
					else
					{
						updatePlayer();
					}
				} 
				else
				{

					if(currentState == State.PAUSED && currentPlugin.getMediaPlayer() != null)
					{
						int latency = currentPlugin.getIntInfo(203);
						if(latency >= 0) {
							if(latency / 1000 != lastLatency / 1000) {
								songDetails.put(SongMeta.BUFFERING, latency);
								Message msg = mHandler.obtainMessage(MSG_PROGRESS, 0, 0);
								mHandler.sendMessage(msg);
								lastLatency = latency;
							}
						}
					}

					if(currentState == State.STOPPED) {
						noPlayWait++;
					}
					Thread.sleep(10);
				}

			} 
			catch (InterruptedException e)
			{
				break;
			}
		}
		if (audioPlayer != null)
		{
			audioPlayer.exit();
			
		}
		

		for(DroidSoundPlugin plugin : plugins) {
			plugin.exit();
		}

		Log.d(TAG, "Player Thread exiting due to inactivity");

	}
	
	private void updatePlayer() throws InterruptedException
	{
		
		noPlayWait = 0;
		if (audioPlayer == null)
		{
			Log.d(TAG,"Null audioPlayer");
		}
		int playPos = audioPlayer.getPlaybackPosition();
		
		if(lastPos > playPos)
			lastPos = -1;
		
		if(playPos >= lastPos + 500)
		{
			songDetails.put(SongMeta.POSITION, playPos);
			Message msg = mHandler.obtainMessage(MSG_PROGRESS, playPos, 0);
			mHandler.sendMessage(msg);
			lastPos = playPos;
		}
		
		
		if(audioPlayer.getLeft() < dataSize * 2)
		{
			//Thread.sleep(100); //used to be 100
			return;
		}
				
		int len = 0;

		short [] samples = audioPlayer.getArray(dataSize);
		
		if(!songEnded)
		{
			len = currentPlugin.getSoundData(samples, dataSize);
		} 
		else 
		{
			//Thread.sleep(100);
			len = dataSize;
			Arrays.fill(samples, 0, len, (short) 0);
			if(audioPlayer.markReached())
			{
				Message msg = mHandler.obtainMessage(MSG_DONE);
				mHandler.sendMessage(msg);
			}
				
		}

		if(len < 0)
		{
			if(playPos > 5000)
			{
				Log.d(TAG, "SONG ENDED HERE");
				songEnded = true;
				audioPlayer.mark();
			}
			
			len = dataSize;
			Arrays.fill(samples, 0, len, (short) 0);
		}
		if(len > 0)
		{
			
			audioPlayer.update(samples, len);
			
			int silence = audioPlayer.getSilence();
			if(silence > 2500)
			{
				if(!isSilent)
				{
					isSilent = true;
					Message msg = mHandler.obtainMessage(MSG_SILENT, silence, 0);
					mHandler.sendMessage(msg);
				}
				Log.d(TAG, "SILENCE %d", silence);
			} 
			else if (isSilent)
			{
				Message msg = mHandler.obtainMessage(MSG_SILENT, 0, 0);
				mHandler.sendMessage(msg);
				isSilent = false;
			}
				
		}

	}
	
	private void updateMediaPlayer() throws InterruptedException {
		
		int playPos = currentPlugin.getSoundData(null, 0);
		
		if(playPos < 0) { // !mp.isPlaying()) {
			// songEnded = true;
			Log.d(TAG, "SOUNDDATA DONE");
			currentState = State.SWITCHING;
			Message msg = mHandler.obtainMessage(MSG_DONE);
			mHandler.sendMessage(msg);
		}

		// Read delayed info
		if(playPos > 0 && firstData) {
			
			String title = getPluginInfo(DroidSoundPlugin.INFO_TITLE);
			String composer = getPluginInfo(DroidSoundPlugin.INFO_AUTHOR);
			
			if(composer != null)
				songDetails.put(SongMeta.COMPOSER, composer);			

			if(title != null)
				songDetails.put(SongMeta.TITLE, title);
			else {
				// Still no title, so build one from the filename		
				String basename = currentPlugin.getBaseName((String) songDetails.get(SongMeta.FILENAME));
				
				int sep = basename.indexOf(" - ");
				if(sep > 0) {
					songDetails.put(SongMeta.COMPOSER, basename.substring(0, sep));
					songDetails.put(SongMeta.TITLE, basename.substring(sep+3));
				} else 
					songDetails.put(SongMeta.TITLE, basename);
			}
			
			Map<String, Object> info = currentPlugin.getDetailedInfo();
			if(info != null) {				 
				Log.d(TAG, "########################################### GOT DETAILS");
				songDetails.putAll(info);
			}
			firstData = false;

			Message msg = mHandler.obtainMessage(MSG_DETAILS, songDetails);
			mHandler.sendMessage(msg);		
		}

		int latency = currentPlugin.getIntInfo(203);
		if(latency >= 0) {
			int diff = latency - lastLatency;
			if(diff < 0)
				diff = -diff;
			if(diff > 1000) {
				songDetails.put(SongMeta.BUFFERING, latency);
				Message msg = mHandler.obtainMessage(MSG_PROGRESS, 0, 0);
				mHandler.sendMessage(msg);
				lastLatency = latency;
			}
		}

		// Check if a new tag has been found (shoutcast metadata)
		if(currentPlugin.getIntInfo(DroidSoundPlugin.INFO_DETAILS_CHANGED) != 0) {

			String title = currentPlugin.getStringInfo(DroidSoundPlugin.INFO_TITLE);
			String composer = currentPlugin.getStringInfo(DroidSoundPlugin.INFO_AUTHOR);

			boolean change = false;
			if(title != null && !title.equals(songDetails.get(SongMeta.TITLE))) {
				songDetails.put(SongMeta.TITLE, title);
				change = true;
			}
			
			if(composer != null && !composer.equals(songDetails.get(SongMeta.COMPOSER))) {				
				songDetails.put(SongMeta.COMPOSER, composer);
				change = true;
			}
			
			if(change) {
				Log.d(TAG, "######## GOT NEW META INFO: %s,%s", title, composer);
				Message msg;
				msg = mHandler.obtainMessage(MSG_DETAILS, songDetails);
				mHandler.sendMessage(msg);
			}			
		}

		// Check if music passed into another subtune (CUE file handling)
		int song = currentPlugin.getIntInfo(DroidSoundPlugin.INFO_SUBTUNE_NO);
		if(song >= 0 && song != currentTune)
		{

			if(startedFromSub)
			{
				Log.d(TAG, "SUBTUNE ENDED");
				currentState = State.SWITCHING;
				Message msg = mHandler.obtainMessage(MSG_DONE);
				mHandler.sendMessage(msg);
				startedFromSub = false;
			}

			currentTune = song;
			songDetails.put(SongMeta.SUBTUNE, currentTune);
			songDetails.put(SongMeta.SUBTUNE_TITLE,  getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_TITLE));
			songDetails.put(SongMeta.SUBTUNE_COMPOSER, getPluginInfo(DroidSoundPlugin.INFO_SUBTUNE_AUTHOR));
			Message msg = mHandler.obtainMessage(MSG_SUBTUNE, songDetails);
			mHandler.sendMessage(msg);
		}

		songDetails.put(SongMeta.POSITION, playPos);
		Message msg = mHandler.obtainMessage(MSG_PROGRESS, 0, 0);
		mHandler.sendMessage(msg);
		Thread.sleep(20);
	}
	
	public void getSongDetails(Map<String, Object> target)
	{
		target.putAll(songDetails);			
	}

	public void stop()
	{
		synchronized (cmdLock)
		{
			commands.add(new Command(CommandName.STOP));
		}
	}
	
	private Object whoPaused = null;

	public void paused(boolean pause) {
		paused(pause, null);
	}

	
	public boolean pausedBy(Object who)
	{
		return (currentState == State.PAUSED) && (whoPaused == who);
	}
	
	public Object paused(boolean pause, Object who)
	{
		
		Object oldWho = whoPaused;
		
		synchronized (cmdLock) {
			if(pause) {
				Log.d(TAG, "Pausing");
				commands.add(new Command(CommandName.PAUSE));
				whoPaused = who;
			} else {
				Log.d(TAG, "Unpausing");
				commands.add(new Command(CommandName.UNPAUSE));
			}
		}
		return oldWho;
	}

	public void seekTo(int pos) {
		synchronized (cmdLock) {
			commands.add(new Command(CommandName.SET_POS, pos));
		}
	}

	public void playMod(SongFile mod) {
		synchronized (cmdLock) {
			commands.add(new Command(CommandName.PLAY, mod));
		}
	}
	
	public void repeatSong() {
		synchronized (cmdLock) {
			commands.add(new Command(CommandName.RESTART));
		}
	}


	public void setSubSong(int song) {
		synchronized (cmdLock) {
			commands.add(new Command(CommandName.SET_TUNE, song));
		}
	}
	
	public void setLoopMode(int what) {
		synchronized (cmdLock) {
			loopMode = what;		
		}
	}

	public boolean isActive() {
		return currentState != State.STOPPED;
	}

	public boolean isPlaying() {
		return (currentState == State.PLAYING);
	}

	public boolean isSwitching() {
		return (currentState == State.SWITCHING);
	}
	
	public byte [] getBinaryInfo(int what) {
		synchronized (binaryInfo) {
			return binaryInfo[what];	
		}
		
	}

	public void dumpWav(SongFile song, String destFile, int length, int flags) {
		synchronized (cmdLock) {
			commands.add(new Command(CommandName.DUMP_WAV, new Object [] {song, new File(destFile), length, flags}));
		}
		
	}
}