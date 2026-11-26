using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading.Tasks;
using System.Collections.Generic;

namespace Famical.Windows
{
    internal sealed class NativeStore : IDisposable
    {
        private readonly object gate = new object();
        private readonly string file;
        private readonly CookieContainer cookies = new CookieContainer();
        private readonly HttpClient http;
        private readonly Uri server;
        private NativeState state;
        private bool syncing;
        internal event Action Changed;
        internal string Status = "기기에 저장됨";
        internal NativeStore(Uri server, string directory)
        {
            this.server = server;
            Directory.CreateDirectory(directory);
            string key;
            using (var hash = SHA256.Create())
                key = BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(server.AbsoluteUri))).Replace("-", "");
            file = Path.Combine(directory, "native-" + key + ".bin");
            state = new NativeState();
            if (File.Exists(file))
            {
                try
                {
                    state = Almanac.Json.Deserialize<NativeState>(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(file), null, DataProtectionScope.CurrentUser)));
                }
                catch (Exception ex) when (ex is CryptographicException || ex is IOException || ex is ArgumentException) { throw new IOException("기기 저장 파일을 읽을 수 없습니다. 원본 파일을 보관하고 확인해 주세요.", ex); }
            }
            if (!string.IsNullOrEmpty(state.cookie))
                cookies.SetCookies(server, state.cookie);
            http = new HttpClient(new HttpClientHandler { CookieContainer = cookies, AllowAutoRedirect = false }) { BaseAddress = server, Timeout = TimeSpan.FromSeconds(15) };
        }
        internal NativeState View()
        {
            lock (gate)
                return Almanac.Json.Deserialize<NativeState>(Almanac.Json.Serialize(state));
        }
        private void Persist()
        {
            state.cookie = cookies.GetCookieHeader(server);
            var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(Almanac.Json.Serialize(state)), null, DataProtectionScope.CurrentUser);
            string temporary = file + ".tmp";
            using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None))
            {
                stream.Write(bytes, 0, bytes.Length);
                stream.Flush(true);
            }
            if (File.Exists(file))
                File.Replace(temporary, file, null);
            else
                File.Move(temporary, file);
        }
        internal static string Key(NativeEvent item) => "s" + item.id;
        internal Dictionary<string, NativeEvent> Items(NativeState view = null)
        {
            view = view ?? View();
            var items = view.events.ToDictionary(Key, e => e);
            foreach (var pending in view.pending)
            {
                if (pending.id.HasValue)
                    items.Remove("s" + pending.id.Value);
                if (pending.action == "save")
                    items[pending.key] = pending.@event;
            }
            return items;
        }
        internal void Save(string key, NativeEvent item, bool delete = false)
        {
            lock (gate)
            {
                var existing = state.pending.FirstOrDefault(p => p.key == key);
                if (existing != null && existing.conflict)
                    throw new InvalidOperationException("설정의 동기화 충돌 항목을 먼저 확인하세요.");
                if (existing == null && item.id == 0 && key != null && state.aliases.TryGetValue(key, out long savedId))
                {
                    var remote = state.events.FirstOrDefault(e => e.id == savedId);
                    item.id = savedId;
                    item.revision = remote?.revision ?? "";
                }
                var pending = new NativeMutation
                {
                    key = key ?? Guid.NewGuid().ToString(),
                    operation = Guid.NewGuid().ToString(),
                    creation_key = existing?.creation_key ?? Guid.NewGuid().ToString(),
                    id = existing?.id ?? (item.id == 0 ? (long?)null : item.id),
                    revision = existing?.revision ?? item.revision,
                    @event = item,
                    action = delete ? "delete" : "save"
                };
                if (existing != null)
                    state.pending.Remove(existing);
                state.pending.Add(pending);
                Persist();
            }
            Changed?.Invoke();
        }
        internal void Preference(string team, bool enabled)
        {
            lock (gate)
            {
                state.user.shift_team = team;
                state.user.shift_on = enabled ? 1 : 0;
                state.preferencesPending = true;
                Persist();
            }
            Changed?.Invoke();
        }
        private async Task<SyncResponse> Request(string path, object body = null, string csrf = null, string method = "POST")
        {
            var request = new HttpRequestMessage(body == null ? HttpMethod.Get : new HttpMethod(method), "api/" + path);
            if (body != null)
            {
                request.Content = new StringContent(Almanac.Json.Serialize(body), Encoding.UTF8, "application/json");
                request.Headers.Add("X-CSRF-Token", csrf);
            }
            using (request)
            using (var response = await http.SendAsync(request))
            {
                var result = Almanac.Json.Deserialize<SyncResponse>(await response.Content.ReadAsStringAsync());
                if (!response.IsSuccessStatusCode)
                    throw new IOException(result.error ?? $"서버 응답 {(int)response.StatusCode}. 재로그인이 필요할 수 있습니다.");
                return result;
            }
        }
        internal async Task Login(string username, string password)
        {
            var me = await Request("me");
            await Request("login", new
            {
                username,
                password,
                remember = true
            }, me.csrf);
            me = await Request("me");
            lock (gate)
            {
                if (state.user != null && state.user.id != me.user.id)
                    throw new IOException("저장된 계정과 다릅니다. 먼저 로그아웃한 뒤 로그인하세요.");
                if (state.preferencesPending && state.user != null)
                {
                    me.user.shift_team = state.user.shift_team;
                    me.user.shift_on = state.user.shift_on;
                }
                state.user = me.user;
                Persist();
            }
            await Sync();
        }
        internal async Task Sync()
        {
            lock (gate)
            {
                if (syncing || state.user == null)
                    return;
                syncing = true;
            }
            Status = "동기화 중…";
            Changed?.Invoke();
            try
            {
                var me = await Request("me");
                if (me.user == null || me.user.id != View().user.id)
                    throw new IOException("로그인이 만료되었습니다. 기기 일정은 사용할 수 있습니다. 설정에서 같은 계정으로 다시 로그인하세요.");
                var view = View();
                if (view.preferencesPending)
                {
                    await Request("preferences", new
                    {
                        shift_team = view.user.shift_team,
                        shift_on = view.user.shift_on != 0
                    }, me.csrf, "PUT");
                    lock (gate)
                    {
                        if (state.user.shift_team == view.user.shift_team && state.user.shift_on == view.user.shift_on)
                            state.preferencesPending = false;
                        Persist();
                    }
                }
                foreach (var pending in View().pending.Where(p => !p.conflict))
                {
                    SyncResponse result;
                    try
                    {
                        result = await Request("native-sync", new
                        {
                            operation = pending.operation,
                            creation_key = pending.creation_key,
                            id = pending.id,
                            revision = pending.revision,
                            action = pending.action,
                            @event = pending.@event
                        }, me.csrf);
                    }
                    catch (IOException ex)
                    {
                        lock (gate)
                        {
                            var current = state.pending.FirstOrDefault(p => p.key == pending.key);
                            if (current != null)
                            {
                                current.error = ex.Message;
                                Persist();
                            }
                        }
                        throw;
                    }
                    lock (gate)
                    {
                        var current = state.pending.FirstOrDefault(p => p.key == pending.key);
                        if (current == null)
                            continue;
                        if (result.conflict)
                        {
                            current.conflict = true;
                            current.current = result.current;
                            current.error = "다른 기기에서 수정·삭제되었습니다.";
                        }
                        else
                        {
                            if (pending.id.HasValue)
                                state.events.RemoveAll(e => e.id == pending.id.Value);
                            if (result.@event != null)
                            {
                                state.events.Add(result.@event);
                                state.aliases[current.key] = result.@event.id;
                            }
                            if (current.operation == pending.operation)
                                state.pending.Remove(current);
                            else
                            {
                                current.id = result.@event?.id;
                                current.revision = result.@event?.revision ?? "";
                                if (result.@event != null)
                                    current.@event.id = result.@event.id;
                            }
                        }
                        Persist();
                    }
                }
                var snapshot = await Request("native-sync?version=" + View().version);
                if (snapshot.protocol != 1)
                    throw new IOException("NAS를 v2.0.0 이상으로 업데이트하세요.");
                lock (gate)
                {
                    if (!snapshot.unchanged)
                        state.events = snapshot.events;
                    state.version = snapshot.version;
                    if (!state.preferencesPending)
                        state.user = snapshot.user;
                    state.synced = DateTime.Now.ToString("MM/dd HH:mm");
                    Persist();
                }
                Status = View().pending.Count == 0 ? "동기화 완료" : $"기기 저장 · 확인 필요 {View().pending.Count}건";
            }
            catch (Exception ex) when (ex is HttpRequestException || ex is TaskCanceledException || ex is IOException || ex is ArgumentException)
            {
                Status = "오프라인 · " + ex.Message;
            }
            finally { lock (gate) syncing = false; Changed?.Invoke(); }
        }
        internal void Resolve(string key, bool keepLocal)
        {
            lock (gate)
            {
                var pending = state.pending.First(p => p.key == key);
                if (!keepLocal)
                {
                    if (pending.conflict && pending.id.HasValue)
                    {
                        state.events.RemoveAll(e => e.id == pending.id.Value);
                        if (pending.current != null)
                            state.events.Add(pending.current);
                    }
                    state.pending.Remove(pending);
                }
                else
                {
                    pending.id = pending.current?.id;
                    if (!pending.id.HasValue)
                        pending.creation_key = Guid.NewGuid().ToString();
                    pending.revision = pending.current?.revision ?? "";
                    pending.@event.id = pending.id ?? 0;
                    pending.operation = Guid.NewGuid().ToString();
                    pending.conflict = false;
                    pending.error = "";
                    if (pending.id == null && pending.action == "delete")
                        state.pending.Remove(pending);
                }
                Persist();
            }
            Changed?.Invoke();
        }
        internal async Task Logout()
        {
            if (syncing)
                throw new IOException("동기화가 끝난 뒤 다시 시도하세요.");
            if (View().pending.Count > 0)
                throw new IOException("미전송 일정이 있습니다. 동기화와 충돌 확인 후 로그아웃하세요.");
            try
            {
                var me = await Request("me");
                await Request("logout", new
                {
                }, me.csrf);
            }
            catch (Exception ex) when (ex is IOException || ex is HttpRequestException || ex is TaskCanceledException) { }
            lock (gate)
            {
                if (state.pending.Count > 0)
                    throw new IOException("미전송 일정이 있습니다.");
                state = new NativeState();
                foreach (Cookie cookie in cookies.GetCookies(server))
                    cookie.Expired = true;
                Persist();
            }
            Changed?.Invoke();
        }
        public void Dispose() => http.Dispose();
    }
}
