/**
 * This class provides convertor from cwa accelerometer format to bin format.
 * Developed by Evgeny M. Mirkes, University of Leicester, 2017.
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

import java.text.SimpleDateFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;


public class cwa2bin {
    /*
     * This class is atatic and does not have constructor.
     */


    public static void main(String[] args) {
        /*
         * Input arguments of this method:
         * fileName is file name for conversion If file name contains asteric * or
         *      question sign ? then this name considered as mask and all mathced
         *      files are proceeded.
         * deviceLocationCode:text contains text to include into Device Location
         *      Code field. If text contains spaces then all argument must be quoted:
         *      "deviceLocationCode:text with spaces".
         */
        if (args.length == 0) {
            System.out.println("Usage:\njava cwa2bin fileName arguments\n" +
                    "where:\nfileName is name of file to process. " +
                    "Can contain wildcards but in this case has to be quoted: \"*.cwa\" to process all cwa files in folder" +
                    "\ndeviceLocationCode:text contains text to include into" +
                    " Device Location Code field.\n If text contains spaces then all argument must be quoted:\n" +
                    " \"deviceLocationCode:text with spaces\"");
            return;
        }

        // Set default values
        String deviceLocationCode = "left wrist";
        // Parse attributes from 2
        for (String s : args) {
            String[] ss = s.split(":");
            ss[0] = ss[0].toLowerCase();
            if ("devicelocationcode".equals(ss[0])) {
		if (ss.length>1)
                    deviceLocationCode = ss[1];
		else {
                    System.out.println("If value of parameter 'devicelocationcode' contains spaces or other special symbols" +
		    " then parameters must be quoted");
		    return;
		}
            }
        }

        // Search for files in accordance with specification
        //Create file and extract path and file name
        File f = new File(args[0]);
        String path = f.getParent();
        String name = f.getName();
        char sep = f.separatorChar;

        if (path == null)
            path = ".";
        f = new File(path);
        path = path + sep;
        String[] flist = f.list();
        if (flist == null)
            return;
        // Prepare file name to search
        String regex = name.replace(".", "\\."); //escape the dot first
        regex = regex.replace("?", ".?").replace("*", ".*");

        for (String s : flist) {
            f = new File(path + s);
            if (!f.isFile() || !s.matches(regex))
                continue;
            System.out.println("processing of the file "+path + s);
            // Processing one file
            convert(args[0], deviceLocationCode);
        }
    }

    /**
     * convert converts one file from cwa format into bin format.
     * @param fName is file name.
     * @param deviceLocationCode is name of device location code.
     */
    public final static void convert(String fName, String deviceLocationCode) {
        // Constants for Bin file
        int vol = 1;
        int lu = 8;
        int gain = 12800;

        // The first loop. Calculation of all information for bin file header:
        // 1. Start time of each cwa page
        // 2. Number of pages with 300 measurements per page
        // 3. Number of measurements in each page
        // 4. Measurement Frequency
        // 5. Firmware Version
        // 6. Device Unique Serial Code

        // Open file for reading
        FileChannel fc = null;
        int bufSize = 512;
        String header;
        String outputFile = fName.split("\\.")[0] + ".bin";

        ByteBuffer buf = ByteBuffer.allocate(bufSize);
        try {
            // Open file
            fc = new FileInputStream(fName).getChannel();
            // Variables to extract data from header and data blocks
            int uniqueSerialCode = 0, firmwareVersion = 0, measurementFrequency = 100, pages = 0;
            ArrayList<Date> dates = new ArrayList<Date>((int)(fc.size() / bufSize));
            ArrayList<Integer> pageLength = new ArrayList<Integer>((int)(fc.size() / bufSize));
            int work, workFreq, timeStamp, fractional, shift, lastPageLength;

            // Read every page in CWA file and extract information
            while (fc.read(buf) != -1) {
                buf.flip();
                buf.order(ByteOrder.LITTLE_ENDIAN);
                header = "" + (char)buf.get() + (char)buf.get();
                if (header.equals("MD")) {
                    // We take
                    // 4. Measurement Frequency
                    measurementFrequency = Math.round(3200 / (1 << (15 - (buf.get(36) & 0xf))));
                    // 5. Firmware Version
                    firmwareVersion = (buf.get(41) & 0xff);
                    // 6. Device Unique Serial Code
                    uniqueSerialCode = buf.getShort(5) & 0xffff;
                } else if (header.equals("AX")) {
                    //Get values to form
                    // 1. Extract time (last time)
                    timeStamp = buf.getInt(14) & 0xffffffff;
                    shift = 0;
                    fractional = 0;
                    // 3. Number of measurements in each page
                    lastPageLength = buf.getShort(28) & 0xffff;
                    pageLength.add(lastPageLength);

                    // Consider two possible formats. Get two important values
                    workFreq = (buf.get(24) & 0xff);
                    work = buf.getShort(4) & 0xffff;
                    // Very old file have buf(24)=0 and frequency in buf(26)
                    if (workFreq != 0) {
                        // buf(26) is index of measurement with whole number of seconds
                        shift = (buf.get(26) & 0xff);
                        /**
                         * If fractional offset, then timestamp offset was artificially
                         * modified for backwards-compatibility ... therefore undo this...
                         */
                        if ((work & 0x8000) != 0) {
                            workFreq = Math.round(3200 / (1 << (15 - (workFreq & 0xf))));
                            /**
                             * Need to undo backwards-compatible shim:
                             * Take into account how many whole samples the fractional part
                             * of timestamp accounts for:
                             * relativeOffset = fifoLength
                             *   - (short)(((unsigned long)timeFractional * AccelFrequency()) >> 16);
                             *     nearest whole sample
                             *    whole-sec   | /fifo-pos@time
                             *       |        |/
                             * [0][1][2][3][4][5][6][7][8][9]
                             */
                            // use 15-bits as 16-bit fractional time
                            fractional = ((work & 0x7fff) << 1);
                            //frequency is truncated to int in firmware
                            shift += ((fractional * workFreq) >> 16);
                        }
                    } else {
                        // Very old format, where pos26 = freq
                        workFreq = buf.getShort(26);
                    }
                    // 4. Measurement Frequency
                    if (measurementFrequency != workFreq) {
                        System.out.print("Inconsistent value of measurement frequency: there is " +
                                         measurementFrequency + " in header and " + workFreq + " in the block " +
                                         dates.size());
                    }
                    // Calculate exact time in the block beginning
                    Date blockTime = getCwaTimestamp(timeStamp, fractional);
                    addMils(blockTime, secs2Milis((float)-shift / workFreq));
                    // 4. Start time of each cwa page
                    dates.add(blockTime);
                }
                buf.clear();
            }
            fc.close();
            // Calculate time of the fictive first measurement after the last block
            {
                int last = dates.size() - 1;
                long t2 = dates.get(last).getTime(), t1 = dates.get(last - 1).getTime();
                int n2 = pageLength.get(last), n1 = pageLength.get(last - 1);
                long t3 = Math.round(((double)(t2 - t1)) / n1 * n2) + t2;
                dates.add(new Date(t3));
            }
            // 2. Number of pages with 300 measurements per page
            // Calculate complete length of time interval
            long interv = dates.get(dates.size() - 1).getTime() - dates.get(0).getTime();
            // Calculate number of measurements which must be written (time in ms / 1000 *frequency)
            int countOfMeasurements = (int)(interv * measurementFrequency / 1000);
            // Calculate number of complete pages
            pages = countOfMeasurements / 300;
            // If we have less then 150 in reminder then we cut them and
            // fill reminder of page by the repetition of the last measurement otherwise.
            if (countOfMeasurements % 300 >= 150)
                pages++;
            /**
             * The secold loop - calibration of frequency and writing bin file
             */
            // Format for timestamps
            SimpleDateFormat timeStampFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss.SSS");
            // Create bin file
            BufferedWriter out = new BufferedWriter(new FileWriter(outputFile));
            // Write header
            out.write("Device Identity");
            out.newLine();
            out.write("Device Unique Serial Code:" + uniqueSerialCode);
            out.newLine();
            out.write("Device Type:Axivity");
            out.newLine();
            out.write("Device Model:");
            out.newLine();
            out.write("Device Firmware Version:CWA converter Axivity firmware " + firmwareVersion);
            out.newLine();
            out.write("Calibration Date:");
            out.newLine();
            out.newLine();
            out.write("Device Capabilities");
            out.newLine();
            out.write("Accelerometer Range:-16 to 16");
            out.newLine();
            out.write("Accelerometer Resolution:" + String.format("%f.4", 100.0 / gain));
            out.newLine();
            out.write("Accelerometer Units:g");
            out.newLine();
            out.write("Light Meter Range:3 to 1000");
            out.newLine();
            out.write("Light Meter Resolution:1");
            out.newLine();
            out.write("Light Meter Units:lux");
            out.newLine();
            out.write("Temperature Sensor Range:0 to 40");
            out.newLine();
            out.write("Temperature Sensor Resolution:0.3");
            out.newLine();
            out.write("Temperature Sensor Units:deg. C");
            out.newLine();
            out.newLine();
            out.write("Configuration Info");
            out.newLine();
            out.write("Measurement Frequency:" + measurementFrequency + " Hz");
            out.newLine();
            out.write("Measurement Period:" +
                      (((int)Math.ceil((double)countOfMeasurements / measurementFrequency / (24 * 60 * 60))) * 24) +
                      " Hours");
            out.newLine();
            out.write("Start Time:" + timeStampFormat.format(dates.get(0)));
            out.newLine();
            out.write("Time Zone:GMT +00:00");
            out.newLine();
            out.newLine();
            out.write("Trial Info");
            out.newLine();
            out.write("Study Centre:");
            out.newLine();
            out.write("Study Code:");
            out.newLine();
            out.write("Investigator ID:");
            out.newLine();
            out.write("Exercise Type:");
            out.newLine();
            out.write("Config Operator ID:");
            out.newLine();
            out.write("Config Time:" + timeStampFormat.format(dates.get(0)));
            out.newLine();
            out.write("Config Notes:Note");
            out.newLine();
            out.write("Extract Operator ID:");
            out.newLine();
            out.write("Extract Time:");
            out.newLine();
            out.write("Extract Notes:(device clock drift 0.000s)");
            out.newLine();
            out.newLine();
            out.write("Subject Info");
            out.newLine();
            out.write("Device Location Code:" + deviceLocationCode);
            out.newLine();
            out.write("Subject Code:" + new File(fName).getName().substring(0, 3));
            out.newLine();
            out.write("Date of Birth:1900-1-1");
            out.newLine();
            out.write("Sex:");
            out.newLine();
            out.write("Height:");
            out.newLine();
            out.write("Weight:");
            out.newLine();
            out.write("Handedness Code:" + deviceLocationCode.split(" ")[0]);
            out.newLine();
            out.write("Subject Notes:");
            out.newLine();
            out.newLine();
            out.write("Calibration Data");
            out.newLine();
            out.write("x gain:" + gain);
            out.newLine();
            out.write("x offset:0");
            out.newLine();
            out.write("y gain:" + gain);
            out.newLine();
            out.write("y offset:0");
            out.newLine();
            out.write("z gain:" + gain);
            out.newLine();
            out.write("z offset:0");
            out.newLine();
            out.write("Volts:" + vol);
            out.newLine();
            out.write("Lux:" + lu);
            out.newLine();
            out.newLine();
            out.write("Memory Status");
            out.newLine();
            out.write("Number of Pages:" + pages);
            out.newLine();
            out.newLine();
            //Prepare data to read. We use rules:
            // 1. Temperature and battery voltage of the last read block are used
            // 2. Light meter value is defined for all records of one block of CWA file.
            // 3. 300 records must be wrote as one page of Bin file
            int maxLeng = 1000; // maximal possible length of arrays
            double temperature = 0, battery = 0;
            int pos = 0; //Position to write the first new value
            int posR = 0; //Position to read the first new value
            long[] times = new long[maxLeng]; //To store times of observations resampled for specified frequency
            double[] x = new double[maxLeng]; //To store acceleration for x axis resampled for specified frequency
            double[] y = new double[maxLeng]; //To store acceleration for y axis resampled for specified frequency
            double[] z = new double[maxLeng]; //To store acceleration for z axis resampled for specified frequency
            int[] light = new int[maxLeng]; //To store vaue of light meter resampled for specified frequency
            int page = 0; //page is number of page to write into Bin file
            int block = 0; //block is number of block to read from CWA file
            //Arrays for non resampled values
            long[] timesR = new long[maxLeng]; //To store times of raw observations
            double[] xR = new double[maxLeng]; //To store raw acceleration for x axis
            double[] yR = new double[maxLeng]; //To store raw acceleration for y axis
            double[] zR = new double[maxLeng]; //To store raw acceleration for z axis
            //Convert previously prepared data
            Date[] dateList = dates.toArray(new Date[dates.size()]);
            dates = null;
            pageLength = null;
            double tmp;
            int NUM_AXES_PER_SAMPLE;
            //Initialize times
            //The first point is the total beginning
            times[0] = dateList[0].getTime();
            //Calculate following times on base of frequency
            resampleTimes(times, measurementFrequency);
            //The second loop. Now calculate all data and write it to the bin file
            // Open file
            fc = new FileInputStream(fName).getChannel();
            while (fc.read(buf) != -1) {
                buf.flip();
                buf.order(ByteOrder.LITTLE_ENDIAN);
                header = "" + (char)buf.get() + (char)buf.get();
                if (header.equals("AX")) {
                    //Get requited data
                    block++;
                    // Number of measurements in each page
                    lastPageLength = buf.getShort(28) & 0xffff;
                    //Read and recalculate temperature
                    temperature = (150.0 * buf.getShort(20) - 20500.0) / 1000.0;
                    battery = 3.0 * (buf.get(23) / 512.0 + 1.0);

                    //Load data which was not resampled
                    interv = dateList[block].getTime();
                    timesR[posR] = dateList[block - 1].getTime();
                    timesR[posR + lastPageLength] = interv;
                    // Get format of data
                    NUM_AXES_PER_SAMPLE = buf.get(25) >> 4;
                    workFreq = buf.get(25) & 0x0f;
                    if (workFreq == 2) {
                        shift = 6; // 3*16-bit
                    } else if (workFreq == 0) {
                        shift = 4; // 3*10-bit + 2
                    } else
                        throw new Exception("Wrong data format at the block " + block);
                    // Let us think that during one block real frequency is constant.
                    // It means that we can calculate time of each observation
                    tmp = ((double)(timesR[posR + lastPageLength] - timesR[posR])) / lastPageLength;
                    for (int i = 0; i < lastPageLength; i++) {
                        // It is time of observation
                        timesR[posR + i] = timesR[posR] + Math.round(tmp * i);
                        // It is observations
                        if (shift == 4) {
                            fractional = buf.getInt(30 + 4 * i);
                            // Sign-extend 10-bit values, adjust for exponents
                            // Get exponent
                            timeStamp = 6 - ((fractional >> 30) & 0x03);
                            xR[posR + i] = ((short)(0xffffffc0 & (fractional << 6)) >> timeStamp);
                            yR[posR + i] = ((short)(0xffffffc0 & (fractional >> 4)) >> timeStamp);
                            zR[posR + i] = ((short)(0xffffffc0 & (fractional >> 14)) >> timeStamp);
                        } else if (shift == 6) {
                            xR[posR + i] = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 0);
                            yR[posR + i] = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 2);
                            zR[posR + i] = buf.getShort(30 + 2 * NUM_AXES_PER_SAMPLE * i + 4);
                        } else {
                            throw new Exception("Incorrect type of data format in block " + block);
                        }
                        xR[posR + i] /= 256.0;
                        yR[posR + i] /= 256.0;
                        zR[posR + i] /= 256.0;
                    }

                    //Search the last time inside this block
                    interv = timesR[posR + lastPageLength - 1];
                    work = pos;
                    for (; work < maxLeng && times[work] < interv; work++)
                        ;
                    //Get light and save it
                    workFreq = (int)Math.round(vol * Math.pow(2, 3.0 * (buf.getShort(18) / 512.0 + 1.0)) / lu);
                    for (int i = pos; i < work; i++)
                        light[i] = workFreq;
                    // Now we must resample observations for normalised frequency
                    interpLinear(timesR, xR, yR, zR, times, x, y, z, pos, work);
                    // Shift pointers
                    pos = work; //work corresponds to the first free position
                    posR = posR + lastPageLength;
                    // Search the first non resampled time which is not greater than times[pos]
                    // This is the first time point which can be used next time
                    for (work = posR; timesR[work] >= times[pos - 1]; work--)
                        ;
                    work -= 0;
                    // Remove the first work elements of raw arrays
                    System.arraycopy(timesR, work, timesR, 0, posR - work);
                    System.arraycopy(xR, work, xR, 0, posR - work);
                    System.arraycopy(yR, work, yR, 0, posR - work);
                    System.arraycopy(zR, work, zR, 0, posR - work);
                    posR -= work;
                    //Check do we have enough data to write page to bin file
                    if (pos >= 300) {
                        out.write("Recorded Data");
                        out.newLine();
                        out.write("Device Unique Serial Code:" + uniqueSerialCode);
                        out.newLine();
                        out.write("Sequence Number:" + page);
                        out.newLine();
                        out.write("Page Time:" + timeStampFormat.format(times[0]));
                        out.newLine();
                        out.write("Unassigned:");
                        out.newLine();
                        out.write("Temperature:" + String.format("%.1f", temperature));
                        out.newLine();
                        out.write("Battery voltage:" + String.format("%.1f", battery));
                        out.newLine();
                        out.write("Device Status:Recording");
                        out.newLine();
                        out.write("Measurement Frequency:" + measurementFrequency + ".0");
                        out.newLine();
                        // Write line of hexadecimal digits from measurements
                        for (int i = 0; i < 300; i++)
                            out.write(packMeasurements(x[i], y[i], z[i], light[i], gain));
                        out.newLine();
                        // Shift data
                        page++;
                        System.arraycopy(times, 300, times, 0, pos - 290);
                        System.arraycopy(x, 300, x, 0, pos - 290);
                        System.arraycopy(y, 300, y, 0, pos - 290);
                        System.arraycopy(z, 300, z, 0, pos - 290);
                        System.arraycopy(light, 300, light, 0, pos - 290);
                        pos -= 290;
                        resampleTimes(times, measurementFrequency);
                    }
                }
                buf.clear();
            }
            out.close();
            fc.close();
        } catch (Exception excep) {
            excep.printStackTrace(System.err);
            System.err.println("Error of reading/writing file " + outputFile + ": " + excep.toString());
            System.exit(-2);
        }
    }

    private static final void resampleTimes(long[] times, int measurementFrequency) {
        //Calculate increment by frequency
        double increment = 1000.0 / measurementFrequency;
        for (int i = 1, n = times.length; i < n; i++)
            times[i] = times[0] + Math.round(increment * i);
    }

    private static Date getCwaTimestamp(int cwaTimestamp, int fractional) {
        int year = ((cwaTimestamp >> 26) & 0x3f) + 2000;
        int month = (cwaTimestamp >> 22) & 0x0f - 1; //Calendar use zero based month!
        int day = (cwaTimestamp >> 17) & 0x1f;
        int hours = (cwaTimestamp >> 12) & 0x1f;
        int mins = (cwaTimestamp >> 6) & 0x3f;
        int secs = (cwaTimestamp) & 0x3f;
        Calendar cal = new GregorianCalendar(year, month, day, hours, mins, secs);
        cal.add(Calendar.MILLISECOND, secs2Milis(fractional / 65536.0));
        return cal.getTime();
    }

    private static int secs2Milis(double num) {
        return (int)(1000 * num);
    }

    private static void addMils(Date dat, int mils) {
        dat.setTime(dat.getTime() + mils);
    }

    //inspired by https://github.com/activityMonitoring/biobankAccelerometerAnalysis/blob/master/java/Resample.java
    // Optimised for used data format.

    public static final void interpLinear(long[] time, // Raw time in milliseconds
        double[] x, double[] y, double[] z, // Raw values of x, y, z
        long[] timeI, // required time in milliseconds
        double[] xNew, double[] yNew, double[] zNew, //Resampled values of x,y,z
        int start, int end) { // Start (inclusive) and stop (exclusive) positions of required points (resampled)

        // Auxiliary variables
        long dt, dT;
        int b = 0;

        // We know that our pointa are sorted in time and timeI
        //Main loop. We have timeI[start]>=time[b]
        for (; start < end; start++) {
            //Search interval for intrpolation
            for (; timeI[start] > time[b]; b++)
                ;
            // time[b-1]<timeI[start]<=time[b]
            if (time[b] == timeI[start]) {
                xNew[start] = x[b];
                yNew[start] = y[b];
                zNew[start] = z[b];
            } else {
                // time[b-1] < timeI[start] < time[b]
                // Calculate auxiliary variables
                dT = time[b] - time[b - 1];
                dt = timeI[start] - time[b - 1];
                xNew[start] = dt * (x[b] - x[b - 1]) / dT + x[b - 1];
                yNew[start] = dt * (y[b] - y[b - 1]) / dT + y[b - 1];
                zNew[start] = dt * (z[b] - z[b - 1]) / dT + z[b - 1];
            }
        }

    }

    public static final String packMeasurements(double x, double y, double z, int light, int gain) {
        long res = 0, tmp;
        res = Math.round(gain * x / 100.0) & 0x0fff;
        tmp = Math.round(gain * y / 100.0) & 0x0fff;
        res = (res << 12) + tmp;
        tmp = Math.round(gain * z / 100.0) & 0x0fff;
        res = (res << 12) + tmp;
        res = ((res << 10) + light) << 2;
        String s = "000000000000" + Long.toHexString(res).toUpperCase();
        return s.substring(s.length() - 12, s.length());
    }
}

