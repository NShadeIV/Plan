/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.gathering.timed;

import com.djrapitops.plan.TaskSystem;
import com.djrapitops.plan.gathering.SystemUsage;
import com.djrapitops.plan.settings.config.PlanConfig;
import com.djrapitops.plan.settings.config.paths.DataGatheringSettings;
import com.djrapitops.plan.utilities.logging.ErrorContext;
import com.djrapitops.plan.utilities.logging.ErrorLogger;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.playeranalytics.plugin.scheduling.RunnableFactory;
import net.playeranalytics.plugin.scheduling.TimeAmount;
import net.playeranalytics.plugin.server.PluginLogger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


/**
 * Task for performing system resource usage checks asynchronously
 *
 * @author AuroraLS3
 */
@Singleton
public class SystemUsageBuffer {

    private static enum GatherSource {
        JAVA,
        PTERODACTYL
    }
    private final GatherSource GATHER_FROM;

    private double cpu = -1.0;
    private long ram = -1L;
    private long freeDiskSpace = -1L;

    @Inject
    public SystemUsageBuffer(PlanConfig config, PluginLogger pluginLogger) {
        GatherSource gatherSource = GatherSource.JAVA;
        try {
            gatherSource = GatherSource.valueOf(config.get(DataGatheringSettings.GATHER_FROM).toUpperCase());
        } catch(Exception e) {
            pluginLogger.warn("Gather_from not recognized");
        }
        this.GATHER_FROM = gatherSource;
    }

    public double getCpu() {
        return cpu;
    }

    public long getRam() {
        return ram;
    }

    public long getFreeDiskSpace() {
        return freeDiskSpace;
    }

    @Singleton
    public static class RamAndCpuTask extends TaskSystem.Task {
        private final SystemUsageBuffer buffer;
        private final PluginLogger logger;
        
        private Boolean enabled;

        @Inject
        public RamAndCpuTask(SystemUsageBuffer buffer, PluginLogger logger) {
            this.buffer = buffer;
            this.logger = logger;
            this.enabled = GatherSource.JAVA.equals(buffer.GATHER_FROM);

            if(this.enabled) {
                SystemUsage.getAverageSystemLoad();
                SystemUsage.getUsedMemory();
            }
        }

        @Override
        public void run() {
            if (!this.enabled) return;
            try {
                buffer.cpu = SystemUsage.getAverageSystemLoad();
                buffer.ram = SystemUsage.getUsedMemory();
            } catch (Exception e) {
                logger.error("RAM & CPU sampling task had to be stopped due to error: " + e.toString());
                cancel();
            }
        }

        @Override
        public void cancel() {
            this.enabled = false;
            super.cancel();
        }

        @Override
        public void register(RunnableFactory runnableFactory) {
            long delay = TimeAmount.toTicks(1, TimeUnit.MINUTES) - TimeAmount.toTicks(500, TimeUnit.MILLISECONDS);
            long period = TimeAmount.toTicks(1, TimeUnit.SECONDS);
            runnableFactory.create(this).runTaskTimerAsynchronously(delay, period);
        }
    }

    @Singleton
    public static class DiskTask extends TaskSystem.Task {
        private final SystemUsageBuffer buffer;
        private final PluginLogger logger;
        private final ErrorLogger errorLogger;

        private Boolean enabled;
        private boolean diskErrored = false;

        @Inject
        public DiskTask(PlanConfig config, SystemUsageBuffer buffer, PluginLogger logger, ErrorLogger errorLogger) {
            this.buffer = buffer;
            this.logger = logger;
            this.errorLogger = errorLogger;
            this.enabled = GatherSource.JAVA.equals(buffer.GATHER_FROM) && config.isTrue(DataGatheringSettings.DISK_SPACE);

            if(this.enabled) SystemUsage.getFreeDiskSpace();
        }

        @Override
        public void run() {
            if (!this.enabled) return;
            try {
                buffer.freeDiskSpace = SystemUsage.getFreeDiskSpace();
            } catch (SecurityException noPermission) {
                if (!diskErrored) {
                    errorLogger.warn(noPermission, ErrorContext.builder()
                            .whatToDo("Resolve " + noPermission.getMessage() + " via OS or JVM permissions").build());
                }
                diskErrored = true;
            } catch (Exception e) {
                logger.error("Free Disk sampling task had to be stopped due to error: " + e.toString());
                cancel();
            }
        }

        @Override
        public void cancel() {
            this.enabled = false;
            super.cancel();
        }

        @Override
        public void register(RunnableFactory runnableFactory) {
            long delay = TimeAmount.toTicks(50, TimeUnit.SECONDS);
            long period = TimeAmount.toTicks(1, TimeUnit.SECONDS);
            runnableFactory.create(this).runTaskTimerAsynchronously(delay, period);
        }
    }
    
    @Singleton
    public static class PteroGatherTask extends TaskSystem.Task {
        private final PlanConfig config;
        private final SystemUsageBuffer buffer;
        private final PluginLogger logger;
        private final OkHttpClient client = new OkHttpClient();

        private final String API_URL;
        private final String API_KEY;
        private final String SERVER_ID;
        private final int FAILS;

        private Boolean enabled = false;
        private int fails = 0;
        private long maxDisk = -1L;

        @Inject
        public PteroGatherTask(PlanConfig config, SystemUsageBuffer buffer, PluginLogger logger) {
            this.buffer = buffer;
            this.config = config;
            this.logger = logger;
            
            this.API_URL = config.get(DataGatheringSettings.PTERO_API_URL);
            this.API_KEY = config.get(DataGatheringSettings.PTERO_API_KEY);
            this.SERVER_ID = config.get(DataGatheringSettings.PTERO_SERVER_ID);
            this.FAILS = config.get(DataGatheringSettings.PTERO_FAILS);

            this.enabled = GatherSource.PTERODACTYL.equals(buffer.GATHER_FROM);

            if(enabled) SystemUsage.getUsedMemory();
        }

        private void fetch(String name, String url, Consumer<JsonObject> consume) {
            Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + API_KEY)
                .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    logger.error("Pterodactyl gathering task errored fetching " + name + ": " + e.toString());
                    if(FAILS > 0 && fails++ > FAILS) cancel();
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            cancel();
                            throw new IOException("Unexpected code " + response);
                        }

                        try {
                            JsonObject json = JsonParser.parseString(responseBody.string()).getAsJsonObject();
                            consume.accept(json);
                        
                            fails = 0;
                        } catch(Exception e) {
                            logger.warn("Pterodactyl gathering task errored parsing " + name + ": " + e.toString());
                            if(FAILS > 0 && fails++ > FAILS) cancel();
                        }
                    }
                }
            });
        }

        private void fetchLimits() {
            fetch("limits", API_URL + "/client/servers/" + SERVER_ID, (JsonObject json) -> {
                maxDisk = json
                    .get("attributes").getAsJsonObject()
                    .get("limits").getAsJsonObject()
                    .get("disk").getAsLong();
            });
        }

        private void fetchUsage() {
            fetch("usage", API_URL + "/client/servers/" + SERVER_ID + "/resources", (JsonObject json) -> {
                JsonObject resources = json
                    .get("attributes").getAsJsonObject()
                    .get("resources").getAsJsonObject();
                buffer.cpu = resources.get("cpu_absolute").getAsDouble();
                buffer.freeDiskSpace = maxDisk - resources.get("disk_bytes").getAsLong() / 1024L / 1024L;

                // Ptero doesn't seem to report accurate memory usage, always reports max memory
                // buffer.ram = resources.get("memory_bytes").getAsLong() / 1024L / 1024L;
            });
        }

        @Override
        public void run() {
            if (!enabled) return;
            try {

                if(maxDisk < 0) fetchLimits();
                else fetchUsage();
                
                // Ptero doesn't seem to report accurate memory usage, always reports max memory
                buffer.ram = SystemUsage.getUsedMemory();

            } catch (Exception e) {
                logger.error("Pterodactyl gathering task had to be stopped due to error: " + e.toString());
                cancel();
            }
        }

        @Override
        public void cancel() {
            enabled = false;
            super.cancel();
        }

        @Override
        public void register(RunnableFactory runnableFactory) {
            try {
                Long requestEvery = config.get(DataGatheringSettings.PTERO_REQUEST_EVERY);

                long delay = TimeAmount.toTicks(50, TimeUnit.SECONDS);
                long period = TimeAmount.toTicks(requestEvery, TimeUnit.MILLISECONDS);
                runnableFactory.create(this).runTaskTimerAsynchronously(delay, period);
            } catch (Exception e) {
                logger.error("Pterodactyl gathering task failed to register: " + e.toString());
            }
        }
    }
}
