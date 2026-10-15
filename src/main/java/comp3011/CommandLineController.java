/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <Bonnorak SUN - a3150511>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;
    // NEW: Stores frame processors in command-line order.
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();
    
    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }
 // NEW: Gives the application the processors in command-line order.
    public List<FrameProcessor> getFrameProcessors(){
    	return new ArrayList<>(frameProcessors);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null && !helpRequested;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            // NEW: Parse long frame-processor options.
            }else if (arg.startsWith("--")) {
            	parseLongOption(arg);
            // NEW: Parse short options, including stacked options such as -jfddn.
            } else if (arg.startsWith("-") && arg.length() > 1) {
                 parseShortOptions(arg);
            } else {
                videoFiles.add(arg);
            }
        }
        
        if (errorMessage != null) {
        	return;
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }
    
    // NEW: Maps long options to frame processors.
    private void parseLongOption(String arg) {
    	switch (arg) {
    	case "--number-frames":
    		frameProcessors.add(new FrameNumberer());
    		break;
    	case "--scratch-frames":
    		frameProcessors.add(new FrameScratcher());
    		break;
    	case "--flicker-frames":
    		frameProcessors.add(new FrameFlickerer());
    		break;
    	case "--black-and-white":
    		frameProcessors.add(new FrameBlackAndWhiter());
    		break;
    	case "--yellow-frames":
    		frameProcessors.add(new FrameYellower());
    		break;
    	case "--vignette-frames":
    		frameProcessors.add(new FrameVignetter());
    		break;
    	case "--dust-frames":
    		frameProcessors.add(new FrameDuster());
    		break;
    	case "--jitter-frames":
    		frameProcessors.add(new FrameJitterer());
    		break;
    	case "--mottle-frames":
    		frameProcessors.add(new FrameMottler());
    		break;
    	case "--bleed-frames":
    		frameProcessors.add(new FrameBleeder());
    		break;
    	case "--pepper-frames":
    		frameProcessors.add(new FramePepperer());
    		break;
    	default:
    		errorMessage = "Unknown option: " + arg;
    	}
    }
    
 // NEW: Parses stacked short options and preserves their order.
    private void parseShortOptions(String arg) {
    	for (int i = 1; i < arg.length(); i++) {
    		char option = arg.charAt(i);
    		
    		switch (option) {
    		case 'h':
    			helpRequested = true;
    			break;
    		case 'a':
    			audioRequested = true;
    			break;
    		case 'x':
    			maximiseRequested = true;
    			break;
    		case 'n':
    			frameProcessors.add(new FrameNumberer());
    			break;
    		case 's':
    			frameProcessors.add(new FrameScratcher());
    			break;
    		case 'f':
    			frameProcessors.add(new FrameFlickerer());
    			break;
    		case 'w':
    			frameProcessors.add(new FrameBlackAndWhiter());
    			break;
    		case 'y':
    			frameProcessors.add(new FrameYellower());
    			break;
    		case 'v':
    			frameProcessors.add(new FrameVignetter());
    			break;
    		case 'd':
    			frameProcessors.add(new FrameDuster());
    			break;
    		case 'j':
    			frameProcessors.add(new FrameJitterer());
    			break;
    		case 'm':
    			frameProcessors.add(new FrameMottler());
    			break;
    		case 'b':
    			frameProcessors.add(new FrameBleeder());
    			break;
    		case 'p':
    			frameProcessors.add(new FramePepperer());
    			break;
    		default:
    			errorMessage = "Unknown option: -" + option;
    			return;
    		}
    	}
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");

    }
}
