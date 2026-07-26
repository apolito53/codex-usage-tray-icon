using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace CodexUsageTray
{
    /// <summary>
    /// Owns the experimental Codex app-server boundary. Keeping JSON-RPC
    /// details here makes a future protocol adjustment a one-file repair.
    /// </summary>
    internal sealed class CodexUsageClient
    {
        private const int ResponseTimeoutMilliseconds = 12000;
        private readonly JavaScriptSerializer _serializer = new JavaScriptSerializer();

        internal Task<UsageSnapshot> GetWeeklyUsageAsync()
        {
            return Task.Run(new Func<UsageSnapshot>(GetWeeklyUsage));
        }

        private UsageSnapshot GetWeeklyUsage()
        {
            string codexPath = CodexPathResolver.Resolve();
            var startInfo = new ProcessStartInfo
            {
                FileName = codexPath,
                Arguments = "app-server --listen stdio://",
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardInput = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            var process = new Process { StartInfo = startInfo };
            Task<string> standardError = null;

            try
            {
                if (!process.Start())
                {
                    throw new InvalidOperationException("The Codex app server did not start.");
                }

                // Drain stderr in the background so a noisy server cannot fill
                // its redirected buffer and stall the tiny request.
                standardError = process.StandardError.ReadToEndAsync();

                WriteRequest(
                    process,
                    "{\"id\":1,\"method\":\"initialize\",\"params\":{" +
                    "\"clientInfo\":{\"name\":\"codex-usage-tray\"," +
                    "\"title\":\"Codex Usage Tray\",\"version\":\"0.1.1.0\"}," +
                    "\"capabilities\":{\"experimentalApi\":true}}}");

                ReadResponse(process, 1);

                WriteRequest(
                    process,
                    "{\"id\":2,\"method\":\"account/rateLimits/read\",\"params\":{}}");

                Dictionary<string, object> response = ReadResponse(process, 2);
                return ParseUsageResponse(response);
            }
            catch (Exception exception)
            {
                string serverDetail = TryGetServerDetail(standardError);
                if (!string.IsNullOrWhiteSpace(serverDetail))
                {
                    throw new InvalidOperationException(
                        exception.Message + " Codex said: " + serverDetail,
                        exception);
                }

                throw;
            }
            finally
            {
                StopProcess(process);
                process.Dispose();
            }
        }

        private static void WriteRequest(Process process, string request)
        {
            process.StandardInput.WriteLine(request);
            process.StandardInput.Flush();
        }

        private Dictionary<string, object> ReadResponse(Process process, int requestId)
        {
            DateTime deadline = DateTime.UtcNow.AddMilliseconds(
                ResponseTimeoutMilliseconds);

            while (DateTime.UtcNow < deadline)
            {
                int remainingMilliseconds = Math.Max(
                    1,
                    (int)(deadline - DateTime.UtcNow).TotalMilliseconds);

                Task<string> lineTask = process.StandardOutput.ReadLineAsync();
                if (!lineTask.Wait(remainingMilliseconds))
                {
                    throw new TimeoutException(
                        "Codex did not answer the usage request in time.");
                }

                string line = lineTask.Result;
                if (line == null)
                {
                    throw new InvalidOperationException(
                        "The Codex app server closed before returning usage.");
                }

                Dictionary<string, object> message;
                try
                {
                    message = _serializer.DeserializeObject(line)
                        as Dictionary<string, object>;
                }
                catch (ArgumentException)
                {
                    // Ignore a non-JSON diagnostic line and keep looking for
                    // the response with our request id.
                    continue;
                }

                if (message == null || !message.ContainsKey("id"))
                {
                    continue;
                }

                int messageId;
                if (!TryConvertInt(message["id"], out messageId) ||
                    messageId != requestId)
                {
                    continue;
                }

                object errorValue;
                if (message.TryGetValue("error", out errorValue) &&
                    errorValue != null)
                {
                    throw new InvalidOperationException(
                        "Codex rejected the usage request: " +
                        ReadProtocolError(errorValue));
                }

                return message;
            }

            throw new TimeoutException(
                "Codex did not answer the usage request in time.");
        }

        private static UsageSnapshot ParseUsageResponse(
            Dictionary<string, object> response)
        {
            Dictionary<string, object> result = GetRequiredDictionary(
                response,
                "result");

            Dictionary<string, object> bucket = FindCodexBucket(result);
            var windows = new List<Dictionary<string, object>>();

            AddWindow(bucket, "primary", windows);
            AddWindow(bucket, "secondary", windows);

            if (windows.Count == 0)
            {
                throw new InvalidDataException(
                    "Codex returned no active rate-limit window.");
            }

            // Historically Codex can report both a short rolling window and a
            // weekly window. Selecting the longest one makes the weekly intent
            // stable across plan shapes.
            Dictionary<string, object> selectedWindow = windows[0];
            long selectedDuration = ReadLong(selectedWindow, "windowDurationMins", 0);

            foreach (Dictionary<string, object> candidate in windows)
            {
                long candidateDuration = ReadLong(
                    candidate,
                    "windowDurationMins",
                    0);

                if (candidateDuration > selectedDuration)
                {
                    selectedWindow = candidate;
                    selectedDuration = candidateDuration;
                }
            }

            int usedPercent = ReadInt(selectedWindow, "usedPercent");
            usedPercent = Math.Max(0, Math.Min(100, usedPercent));

            long resetUnixSeconds = ReadLong(selectedWindow, "resetsAt", 0);
            DateTime? resetAtLocal = resetUnixSeconds > 0
                ? (DateTime?)UnixSecondsToLocalTime(resetUnixSeconds)
                : null;

            string limitId = ReadOptionalString(bucket, "limitId");
            string limitName = ReadOptionalString(bucket, "limitName");
            bool isWeekly = selectedDuration >= (7 * 24 * 60) - 60;
            int? availableResetCredits = ReadAvailableResetCredits(result);

            return new UsageSnapshot(
                usedPercent,
                100 - usedPercent,
                resetAtLocal,
                selectedDuration,
                isWeekly,
                availableResetCredits,
                limitId,
                limitName,
                DateTime.Now);
        }

        private static int? ReadAvailableResetCredits(
            Dictionary<string, object> result)
        {
            object summaryValue;
            if (!result.TryGetValue("rateLimitResetCredits", out summaryValue) ||
                summaryValue == null)
            {
                return null;
            }

            var summary = summaryValue as Dictionary<string, object>;
            if (summary == null)
            {
                return null;
            }

            object countValue;
            int count;
            if (!summary.TryGetValue("availableCount", out countValue) ||
                !TryConvertInt(countValue, out count))
            {
                return null;
            }

            return Math.Max(0, count);
        }

        private static Dictionary<string, object> FindCodexBucket(
            Dictionary<string, object> result)
        {
            object bucketsValue;
            var buckets = result.TryGetValue("rateLimitsByLimitId", out bucketsValue)
                ? bucketsValue as Dictionary<string, object>
                : null;

            if (buckets != null)
            {
                object codexValue;
                if (buckets.TryGetValue("codex", out codexValue))
                {
                    var codexBucket = codexValue as Dictionary<string, object>;
                    if (codexBucket != null)
                    {
                        return codexBucket;
                    }
                }
            }

            return GetRequiredDictionary(result, "rateLimits");
        }

        private static void AddWindow(
            Dictionary<string, object> bucket,
            string propertyName,
            ICollection<Dictionary<string, object>> windows)
        {
            object value;
            if (!bucket.TryGetValue(propertyName, out value) || value == null)
            {
                return;
            }

            var window = value as Dictionary<string, object>;
            if (window != null)
            {
                windows.Add(window);
            }
        }

        private static Dictionary<string, object> GetRequiredDictionary(
            Dictionary<string, object> parent,
            string propertyName)
        {
            object value;
            if (!parent.TryGetValue(propertyName, out value))
            {
                throw new InvalidDataException(
                    "Codex usage data omitted '" + propertyName + "'.");
            }

            var dictionary = value as Dictionary<string, object>;
            if (dictionary == null)
            {
                throw new InvalidDataException(
                    "Codex usage field '" + propertyName + "' had an unexpected shape.");
            }

            return dictionary;
        }

        private static int ReadInt(
            Dictionary<string, object> source,
            string propertyName)
        {
            object value;
            int converted;
            if (!source.TryGetValue(propertyName, out value) ||
                !TryConvertInt(value, out converted))
            {
                throw new InvalidDataException(
                    "Codex usage data omitted numeric '" + propertyName + "'.");
            }

            return converted;
        }

        private static long ReadLong(
            Dictionary<string, object> source,
            string propertyName,
            long fallback)
        {
            object value;
            if (!source.TryGetValue(propertyName, out value) || value == null)
            {
                return fallback;
            }

            try
            {
                return Convert.ToInt64(value, CultureInfo.InvariantCulture);
            }
            catch (Exception exception)
            {
                if (exception is FormatException ||
                    exception is InvalidCastException ||
                    exception is OverflowException)
                {
                    return fallback;
                }

                throw;
            }
        }

        private static string ReadOptionalString(
            Dictionary<string, object> source,
            string propertyName)
        {
            object value;
            return source.TryGetValue(propertyName, out value) && value != null
                ? Convert.ToString(value, CultureInfo.InvariantCulture)
                : null;
        }

        private static bool TryConvertInt(object value, out int converted)
        {
            try
            {
                converted = Convert.ToInt32(value, CultureInfo.InvariantCulture);
                return true;
            }
            catch (Exception exception)
            {
                if (exception is FormatException ||
                    exception is InvalidCastException ||
                    exception is OverflowException)
                {
                    converted = 0;
                    return false;
                }

                throw;
            }
        }

        private static DateTime UnixSecondsToLocalTime(long seconds)
        {
            var epoch = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);
            return epoch.AddSeconds(seconds).ToLocalTime();
        }

        private static string ReadProtocolError(object errorValue)
        {
            var error = errorValue as Dictionary<string, object>;
            if (error == null)
            {
                return "unknown protocol error";
            }

            object messageValue;
            return error.TryGetValue("message", out messageValue)
                ? Convert.ToString(messageValue, CultureInfo.InvariantCulture)
                : "unknown protocol error";
        }

        private static string TryGetServerDetail(Task<string> standardError)
        {
            if (standardError == null || !standardError.IsCompleted)
            {
                return null;
            }

            string detail;
            try
            {
                detail = standardError.Result;
            }
            catch
            {
                return null;
            }

            if (string.IsNullOrWhiteSpace(detail))
            {
                return null;
            }

            detail = detail.Trim().Replace(Environment.NewLine, " ");
            return detail.Length <= 300 ? detail : detail.Substring(0, 300);
        }

        private static void StopProcess(Process process)
        {
            try
            {
                if (process.HasExited)
                {
                    return;
                }

                process.StandardInput.Close();
                if (!process.WaitForExit(500))
                {
                    process.Kill();
                    process.WaitForExit(1000);
                }
            }
            catch
            {
                // The useful result or exception has already been captured.
            }
        }
    }
}
