# Identity — Xác thực, tài khoản, tổ chức và phân quyền

Tài liệu bàn giao tích hợp cho FE, đối chiếu controller/DTO/service hiện tại ngày 17/10/2026; baseline giao diện là Mockup v2 đã lưu cục bộ. Phạm vi chỉ gồm xác thực, hồ sơ, trạng thái, membership, role/scope và kiểm soát truy cập. Không gồm khóa học, giao khóa, thi, tiến độ, báo cáo đào tạo, import user, SMTP, quên mật khẩu, invite hoặc SSO integration.

## 1. Quy ước tích hợp

- Prefix `/api/v1`. Request JSON trực tiếp, không bọc `data`. Dùng `Authorization: Bearer <accessToken>` sau login.
- Response theo [API_CONTRACT](API_CONTRACT.md). Thành công: HTTP 200/201 và `status=SUCCESS`; lỗi validation/nghiệp vụ: HTTP 200 và `status=ERROR`; lỗi xác thực/phân quyền/hệ thống dùng HTTP tương ứng. `messageCode` dùng phân loại lỗi, không thay HTTP status. Response quản trị mặc định có `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, do SecurityConfig quản lý chung.
- ID cán bộ luôn là chuỗi, giữ số 0 đầu. Username lưu chữ thường. Login nhận mã cán bộ hoặc username trong field `username`; chưa hỗ trợ email login.
- List trả `data={items,page,size,totalElements,totalPages}`; page bắt đầu 0, size mặc định 20, tối đa 100. Lọc scope trước tính tổng/phân trang. Sort dạng `field,asc` hoặc `field,desc`; có ID làm thứ tự phụ ổn định.
- PATCH: field bị bỏ qua giữ nguyên; JSON null xóa field nullable. Field ngoài whitelist trả HTTP 200/ERROR với errors[].code=UNSUPPORTED_FIELD. Ví dụ đổi `orgId` và bỏ nhóm cũ phải gửi cả `teamId:null`; nếu nhóm cũ không thuộc org mới, backend trả HTTP 200/ERROR với mã IDENTITY-MEMBERSHIP_INVALID.
- X-Request-Id tùy chọn, lấy responseId khi báo lỗi. Không log/lưu password hoặc token vào console, local analytics hoặc tài liệu.
- 401 tại login: hiển thị lỗi đăng nhập theo bảng bên dưới. 401 khi dùng token: xóa token và về login. 403: phân nhánh theo messageCode; IDENTITY-PASSWORD_CHANGE_REQUIRED chuyển sang đổi mật khẩu, CORE-ACCESS_DENIED hiển thị không có quyền. Không retry lặp hoặc xóa token chỉ vì nhận 403. HTTP 200/ERROR có thể là validation, xung đột hoặc dữ liệu không tìm thấy; xử lý theo messageCode và errors. Request bị lỗi không được coi là đã lưu một phần.

### Luồng xác thực và hồ sơ

JWT mặc định có thời hạn **600 phút (36.000 giây)** qua `app.jwt.expiry-minutes` trong `application.yml`.
Token đã cấp giữ hạn cũ; đăng nhập lại sau khi deploy để nhận thời hạn mới. FE dùng hạn từ token/response,
không tự kéo dài phiên. Đăng xuất, khóa tài khoản và thu hồi phiên vẫn có hiệu lực trước hạn này.

`POST /auth/login` nhận `{"username":"super.admin","password":"<password>"}` và trả `data={accessToken,tokenType,expiresInSeconds}`. JWT nằm tại `data.accessToken`, `tokenType` là `Bearer`, `expiresInSeconds` là thời hạn tính bằng giây. Login không trả hồ sơ, quyền hoặc cờ đổi mật khẩu; FE gọi `/auth/me` tiếp theo. Không gửi Authorization cũ khi đăng nhập. Chưa có refresh token hoặc đăng nhập SSO; tài khoản SSO không xác thực bằng mật khẩu LOCAL.

| HTTP / messageCode | Ý nghĩa | FE xử lý |
|---|---|---|
| 200 / CORE-VALIDATION_FAILED | Thiếu hoặc sai định dạng trường đầu vào | Hiển thị lỗi từ errors theo field |
| 401 / IDENTITY-CREDENTIALS_INVALID | Thông tin đăng nhập không hợp lệ | Hiển thị “Tên đăng nhập hoặc mật khẩu không đúng.”; cho nhập lại |
| 401 / CORE-UNAUTHENTICATED | Thiếu/sai/hết hạn/thu hồi token khi gọi API được bảo vệ | Xóa phiên local, điều hướng đăng nhập |
| 403 / IDENTITY-PASSWORD_CHANGE_REQUIRED | Tài khoản chưa đổi mật khẩu tạm | Giữ token để gọi đổi mật khẩu, chuyển sang màn đổi mật khẩu |
| 403 / CORE-ACCESS_DENIED | Không được phép thực hiện thao tác | Hiển thị thiếu quyền, không tự đăng xuất |
| 200 / IDENTITY-CURRENT_PASSWORD_INCORRECT | Mật khẩu hiện tại không đúng khi đổi mật khẩu | Giữ phiên, hiển thị lỗi tại currentPassword |
| 200 / CORE-VALIDATION_FAILED với errors[].code=SAME_AS_CURRENT | Mật khẩu mới trùng mật khẩu hiện tại | Hiển thị lỗi tại newPassword |

Tài khoản không tồn tại, mật khẩu sai hoặc tài khoản LOCAL không được phép đăng nhập dùng chung mã/thông báo credentials; không thông báo riêng để dò tài khoản. Nếu login gửi kèm Bearer token không hợp lệ, tầng Security có thể từ chối với CORE-UNAUTHENTICATED trước khi xử lý body.

`GET /auth/me` trả `user,roles,functions,roleGrants,functionDetails,functionScopes`:

- `user`: mã cán bộ, username, họ tên, email, trạng thái, nguồn xác thực, cờ đổi mật khẩu, chức danh, mã/tên tổ chức và nhóm; metadata nguồn BRN/BRCD/CRA/dept/bds chỉ đọc.
- `roles`/`functions`: mã vai trò/chức năng để dựng giao diện. `roleGrants` gắn từng vai trò với scope; `functionDetails`/`functionScopes` bổ sung nhãn và phạm vi theo chức năng.
- `functionDetails` chỉ gồm chức năng active, hợp nhất không trùng, sắp theo sortNo/id và có route cho menu. Không trả passwordHash, JWT hoặc auth_ver trong hồ sơ.
- JWT có claim roles là ảnh chụp mã vai trò lúc phát token; FE không dùng claim này để quyết định quyền hiện tại. Hồ sơ và quyền lấy từ /auth/me; backend đọc database và kiểm tra action/scope ở từng use case.

`PATCH /auth/me` chỉ sửa hồ sơ LOCAL. `PUT /auth/password` kiểm tra currentPassword và newPassword khác mật khẩu cũ, từ 6 ký tự và tối đa 72 byte UTF-8; thành công thu hồi mọi phiên và yêu cầu login lại.

### Đổi mật khẩu tạm và khôi phục phiên FE

1. Login thành công: lưu token, gọi `GET /api/v1/auth/me` trước khi tải API quản trị.
2. Nếu `data.user.mustChangePassword=true`: chuyển sang màn đổi mật khẩu. `roles`, `functions`, `roleGrants`, `functionDetails` đều là `[]`; `functionScopes` là `{}`. Không hiểu trạng thái này là tài khoản đã mất quyền.
3. Các API vẫn dùng được với token hợp lệ: `GET /auth/me`, `PUT /auth/password`, `POST /auth/logout`. `PATCH /auth/me` và toàn bộ API quản trị user/role/org/team bị chặn với HTTP 403, mã `IDENTITY-PASSWORD_CHANGE_REQUIRED` khi request hợp lệ tới kiểm tra nghiệp vụ. Validation hoặc lỗi giao thức có thể được trả trước kiểm tra này.
4. Gửi `PUT /api/v1/auth/password` với body trực tiếp bên dưới; không thêm field confirmPassword vào request. Nếu UI có xác nhận mật khẩu, FE tự kiểm tra khớp trước khi gửi.
5. Chỉ khi đổi mật khẩu trả `status=SUCCESS`, xóa token và cache hồ sơ/quyền, yêu cầu đăng nhập lại. Nếu lỗi, giữ phiên để người dùng sửa form hoặc đăng xuất.
6. Khi mở lại ứng dụng với token đã lưu, gọi `/auth/me` để xác định lại trạng thái; không bỏ qua màn đổi mật khẩu dựa trên state FE cũ. Nếu nhận 401, xóa phiên và về login.

```json
{
  "currentPassword": "<mat-khau-hien-tai>",
  "newPassword": "<mat-khau-moi>"
}
```

Cờ bắt buộc đổi mật khẩu được bật khi tạo user LOCAL qua API hoặc tạo root qua bootstrap. Riêng 7 tài khoản seed demo trong learning_hub_identity.sql có must_change_password=false để sử dụng ngay sau đăng nhập. Đổi mật khẩu thành công đặt cờ về false. Hiện chưa có cơ chế hết hạn mật khẩu định kỳ, reset mật khẩu qua quản trị hoặc gửi email khôi phục. Tài khoản không có role vẫn có thể xem hồ sơ, đổi mật khẩu và đăng xuất; không có quyền gọi API quản trị.

`POST /auth/logout` không cần body, yêu cầu token còn hiệu lực và chỉ thu hồi phiên của token đó (claim `sid`, bảng `revoked_sessions`); phiên ở trình duyệt khác vẫn dùng được, phòng học và trình chạy SCORM của phiên đó dừng. Token cũ chưa có `sid` thì thu hồi mọi phiên qua auth_ver. Đổi mật khẩu, vai trò hoặc trạng thái vẫn thu hồi mọi phiên. FE xóa token/cache khi thành công hoặc nhận 401. Request đang xử lý trước thời điểm thu hồi có thể hoàn tất; token mới phát sau khi thu hồi vẫn hợp lệ. Chưa có thao tác kết thúc phiên trên hệ thống SSO bên ngoài.

### Hiển thị lỗi nghiệp vụ trên form

Trùng mã cán bộ/username, mã tổ chức, mã nhóm và lỗi membership/role/scope có errors theo field. FE hiển thị errors[].message tại ô nhập/selector tương ứng; field lồng nhau có dạng initialRoleGrants[0].teamId. Một lỗi thì message ngoài giống thông báo chi tiết, nhiều lỗi thì message ngoài tổng quát. Không cần tách messageCode cho từng field hoặc gọi API kiểm tra trùng riêng; xem bảng ánh xạ trong [API_CONTRACT](API_CONTRACT.md#chi-tiết-lỗi-nghiệp-vụ-theo-field). Các mã nghiệp vụ và HTTP status giữ nguyên.

### Cấu trúc dữ liệu FE cần phân biệt

Các cấu trúc dưới đây nằm trong `data` của BaseResponse; `errors` chỉ xuất hiện khi có chi tiết lỗi. Dùng `status` để xác định kết quả, `messageCode` để chọn cách xử lý; không so sánh chuỗi `message`/`messageTitle`.

| Cấu trúc | Field và ý nghĩa |
|---|---|
| MeResponse | `user`, `roles: string[]`, `functions: string[]`, `roleGrants`, `functionDetails`, `functionScopes` |
| RoleGrant trong /auth/me | `{roleCode,roleName,scope:{type,orgId,teamId}}`; không có grantId hoặc revocable |
| FunctionInfo | `{id,code,name,routePattern,parentFunctionId,sortNo}`; routePattern có thể null, không tự tạo route từ code |
| functionScopes | Object có key là mã chức năng, value là danh sách `{type,orgId,teamId}`; có thể có nhiều scope cho cùng chức năng |
| UserDetail trong /users | `{user,roleGrants,editable}`; roleGrants là danh sách ManagedRoleGrant được phép thấy |
| ManagedRoleGrant | `{grantId,roleId,roleCode,roleName,scope:{type,orgId,teamId},orgName,teamName,grantedBy,grantedAt,revocable}`; dùng grantId khi thu hồi |
| OrganizationInfo | `{id,parentId,orgType,code,name,status,sortNo,memberCount,teamCount}` |

`user` của /auth/me và UserDetail dùng cùng UserInfo: `userId,username,fullName,email,status,authProvider,mustChangePassword,orgId,orgCode,orgName,orgType,teamId,teamCode,teamName,positionName`. Metadata nguồn gồm `brn,brcd,cra,brnName,brcdName,craName,deptCode,deptName,bdsCode,bdsName,qlnsBrnCode,lastSyncTime`; FE không cần hiển thị mọi metadata trên form. `authProvider` là `LOCAL` hoặc `SSO`; org/team có thể null. Timestamp là chuỗi ISO-8601, có thể null nếu chưa có dữ liệu nguồn.

Không dùng roleGrants của /auth/me để dựng thao tác thu hồi: lấy UserDetail qua `/users/{userId}`. Không nhầm `roles` (mã role) với `roleId`, hoặc `functions` (mã chức năng) với `functionDetails` (thông tin menu). Danh mục `/roles` chỉ dành cho quản trị viên; học viên/giảng viên/người xem báo cáo dùng `/auth/me` để dựng giao diện của mình.

## 2. Form tạo/sửa user

| Field | Tạo mới | Sửa | UX/validation |
|---|---|---|---|
| userId | Bắt buộc | Chỉ đọc | Mã cán bộ 1..20; chữ/số/dấu chấm/gạch dưới/gạch ngang; không trùng mã hoặc username khác |
| username | Bắt buộc | Chỉ đọc | 1..100, cùng tập ký tự; backend lowercase; không trùng các định danh hiện có |
| fullName | Bắt buộc | Có | Không trắng, tối đa 255 |
| email | Tùy chọn | Có | Email hợp lệ, tối đa 100; chưa dùng gửi mail hoặc xác thực email |
| positionName | Tùy chọn | Có | Tối đa 255; chỉ mô tả chức danh, không tự cấp quyền |
| orgId | Chọn node tổ chức | Có | ADMIN ORG chỉ chọn trong scope. Global admin có thể để trống |
| teamId | Tùy chọn | Có | Chỉ tải nhóm thuộc đúng orgId; đổi org phải chọn lại hoặc bỏ nhóm |
| initialPassword | Bắt buộc | Không | LOCAL; từ 6 ký tự và tối đa 72 byte UTF-8 do BCrypt; không tự gửi mail |
| initialRoleGrants | Tùy chọn | Tab quyền riêng | Tối đa 20 trong request tạo; mỗi dòng gồm roleId + scope + target; validate cùng transaction |

Tạo mới chỉ tạo LOCAL active. Không có grant mặc định ẩn; muốn LEARNER phải gửi grant OWN. Một lỗi trong danh sách initialRoleGrants rollback cả user.

```json
{
  "userId": "00183617",
  "username": "example.user",
  "fullName": "Nguyễn Văn A",
  "email": "example@company.vn",
  "positionName": "Chuyên viên kỹ thuật",
  "orgId": 12,
  "teamId": 21,
  "initialPassword": "<temporary-password-6-characters-or-more>",
  "initialRoleGrants": [{"roleId": 1, "scope": "OWN"}]
}
```

Không hardcode roleId từ ví dụ; tải `/roles`. Sau tạo, user nhận `mustChangePassword=true`; FE cần cung cấp mật khẩu tạm qua kênh nội bộ được thống nhất. Password không xuất hiện trong response. Login xong gọi `/auth/me`: nếu flag true, điều hướng sang đổi mật khẩu, không cho tiếp tục vào quản trị. Trong thời gian này `/me` trả rights rỗng; backend chặn các thao tác quản trị và sửa hồ sơ. Đổi mật khẩu thành công thu hồi mọi token, FE xóa token và yêu cầu login lại.

PATCH quản trị user nhận `fullName,email,positionName,orgId,teamId`. PATCH hồ sơ cá nhân chỉ nhận `fullName,email,positionName`. Với SSO, các field này readonly trong demo và thay đổi bị từ chối 403. Các field BRN/BRCD/CRA/dept/bds nguồn giữ readonly. Không dùng chức danh trưởng nhóm/trưởng phòng để suy ra ADMIN.

`user` còn giữ metadata nguồn như `/auth/me`; field null có thể xuất hiện. `editable` và `revocable` là hint UI, backend vẫn kiểm tra ở từng thao tác; SSO profile readonly bất kể role. List `/users` trả UserSummary phẳng trong items: userId,username,fullName,email,status,authProvider,mustChangePassword,orgId,orgName,orgType,teamId,teamName,positionName,roleCodes,editable. roleCodes là nhiều role actor được phép thấy, không phải toàn bộ grant. Mở chi tiết mới tải scope/grants; không cần gọi chi tiết cho từng dòng để dựng cột role. Filter roleCode cũng chỉ xét grant actor được phép thấy. Không tự suy role từ chức danh hoặc group.

Trạng thái canonical: `active` (hoạt động), `inactive` (tạm dừng), `locked` (khóa). Bỏ giá trị mock `suspended`. PATCH `/status` riêng, không nhét vào PATCH hồ sơ. Không tự khóa/tạm dừng tài khoản đang thao tác. Khi đổi trạng thái, token cũ của target bị thu hồi.

Chuyển membership: FE cảnh báo **mọi grant khác OWN của target sẽ bị thu hồi**, sau đó quản trị viên gán lại grant cần thiết. Không tự di chuyển hoặc mở rộng grant cũ. Kiểm tra scope nguồn và đích; không cho tự chuyển tổ chức. Chỉ SUPER_ADMIN được sửa trạng thái, tổ chức/nhóm hoặc hồ sơ quản trị của tài khoản mang role ADMIN/SUPER_ADMIN; ADMIN GLOBAL cũng bị giới hạn này. Tự sửa hồ sơ cá nhân dùng /auth/me. Mọi thay đổi và audit ghi cùng transaction. Hộp xác nhận phải nêu rõ phạm vi quyền sẽ bị thu hồi.

## 3. Cây tổ chức và nhóm

- `orgs` gồm UNIT (đơn vị) và DEPARTMENT (phòng). UNIT có thể là root hoặc dưới UNIT; DEPARTMENT bắt buộc có parent UNIT. Không bắt buộc đơn vị phải có phòng trung gian.
- `teams` là nhóm chuyên môn thuộc node DEPARTMENT (ban/phòng), ví dụ tiền gửi/tiền vay/core/tích hợp. Không tạo nhóm mới trực tiếp dưới UNIT; nhóm cũ ở UNIT vẫn đọc và quản lý được trong khi chờ chuẩn hóa dữ liệu. Team của user phải có `team.orgId == user.orgId`.
- Không suy cây từ BRN/BRCD/CRA; đó là thông tin nguồn trên hồ sơ.
- Tree dùng `GET /orgs/tree` để tải gốc trong scope; mở node bằng `/orgs/tree?parentId=<id>`. Trả PageResponse<OrganizationTreeNode>, mỗi node có `id,parentId,orgType,code,name,status,sortNo,hasChildren`. Mỗi cấp có phân trang, sortNo rồi ID tăng dần; FE tải tiếp khi còn trang. hasChildren chỉ xét con trong scope, không tải trước toàn bộ cây. Global admin xem cả node inactive; ADMIN ORG chỉ thấy nhánh active được cấp. Node gốc trong scope có thể có parentId ở ngoài scope, FE không tự tải cha đó.
- Selector/search tiếp tục dùng `/orgs`; kết quả tìm kiếm là danh sách phẳng có parentId. API `/orgs/tree` phục vụ cây lazy-load và hasChildren; không thêm API departments riêng hoặc lấy ancestor ngoài scope.
- Tạo/sửa gồm code,name,orgType,parentId,sortNo theo API; orgType/parentId chỉ có khi tạo. Code org duy nhất không phân biệt hoa/thường ở service. Code team tùy chọn, không trùng trong cùng org.
- Demo chỉ global admin tạo org và đổi status org; ADMIN ORG sửa code/name/sortNo trong scope. Chưa hỗ trợ chuyển node, đổi loại node hoặc xóa org.
- ORG scope gồm node được gán và **cây con active**. Node inactive, ancestor inactive hoặc đường cha bị hỏng không cấp quyền quản trị ORG. Vô hiệu hóa org không tự sửa trạng thái từng user; global admin vẫn thấy node và có thể kích hoạt lại.
- Counts `memberCount`, `teamCount` tại `/orgs` là số lượng trực tiếp của node, không phải cộng toàn cây. `/users?orgId=...` lọc node và cây con active; dùng teamId khi xem thành viên nhóm.
- `/orgs/{id}` trả `{organization: OrganizationInfo, administrators:[...]}`. Administrators là tối đa 20 ADMIN ORG gán trực tiếp tại node, không gộp global admin hoặc quyền kế thừa. Quản trị chi tiết qua user/role-grants.
- Khi chọn Ban/Phòng, gọi `GET /orgs/{id}/contents?page=0&size=10&sort=name,asc`: trả `{organization, administrators, teams}`. organization/administrators giữ cùng cấu trúc với GET chi tiết; teams là PageResponse<TeamInfo> gồm đúng nhóm có orgId=node được chọn, tối đa 5 thành viên xem trước mỗi nhóm. Không cộng nhóm/thành viên từ phòng con và không tự mở rộng xuống cây con. Danh sách nhân sự không nằm trong response này; `/users?orgId=...` hiện có ngữ nghĩa cả cây con active, không dùng thay danh sách nhân sự trực tiếp. Nhóm và thống kê có thể thay đổi giữa các request; không coi preview là danh sách đầy đủ.
- TeamInfo: `{id,orgId,code,name,description,status,memberCount,memberPreview:[{userId,fullName}]}`; preview tối đa 5, nút “Xem tất cả” dùng `/users?teamId=...`.
- Form team có code/name/description. Không chuyển org hoặc đổi trạng thái team trong demo. Xóa chỉ khi không có member, role grant hoặc dữ liệu Learning tham chiếu, kể cả grant đã thu hồi và snapshot học tập (HTTP 200/ERROR, mã ORGANIZATION-TEAM_IN_USE nếu còn tham chiếu lịch sử). Không tự xóa user/grant/tiến độ theo cascade.

### Luồng cây và panel tổ chức

```http
GET /api/v1/orgs/tree?page=0&size=20
GET /api/v1/orgs/tree?parentId=1&page=0&size=20
GET /api/v1/orgs/1/contents?page=0&size=6&sort=name,asc
```

Ví dụ data của API contents (các ID/số lượng chỉ minh họa):

```json
{
  "organization": {
    "id": 1,
    "parentId": null,
    "orgType": "UNIT",
    "code": "DEMO_BAN",
    "name": "Ban Core Banking",
    "status": "active",
    "sortNo": 0,
    "memberCount": 7,
    "teamCount": 1
  },
  "administrators": [],
  "teams": {
    "items": [
      {
        "id": 10,
        "orgId": 1,
        "code": "DEP",
        "name": "Nhóm tiền gửi",
        "description": null,
        "status": "active",
        "memberCount": 0,
        "memberPreview": []
      }
    ],
    "page": 0,
    "size": 6,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

Đổi node phải reset trang nhóm về 0. Node không tồn tại/ngoài scope trả cùng CORE-RESOURCE_NOT_FOUND,
status=ERROR theo envelope chung; chưa đăng nhập trả 401, không có quyền quản trị trả 403.
Các API list/detail/teams cũ vẫn giữ contract, không bắt buộc FE đổi các màn khác.

<a id="organization-import"></a>

### Nhập đơn vị/phòng và nhóm bằng Excel

Trong hộp thoại **Nhập từ tệp**, thêm nút **Tải mẫu Excel**. Mẫu V1 lưu tại
`src/main/resources/templates/organization-import-template.xlsx`, được đóng gói cùng ứng dụng. API đọc resource
bằng stream, hoạt động trong JAR/Docker; không sinh lại mẫu hoặc tải mẫu từ S3. Download cần Bearer token
và quyền quản trị. Import đọc Excel bằng Apache POI (`poi-ooxml`, `XSSFWorkbook`).

Có thể chỉnh hướng dẫn, độ rộng cột và style trực tiếp bằng Excel. Phải giữ tên sheet/header, mã dạng text,
dropdown và hai sheet dữ liệu trống; ví dụ chỉ đặt ở Instructions. Khi đổi cấu trúc cột cần sửa parser,
contract và kiểm tra lại import cùng thay đổi. Rebuild/redeploy để cập nhật mẫu được đóng gói.

```http
GET /api/v1/orgs/import-template
POST /api/v1/orgs/import?dryRun=true
POST /api/v1/orgs/import?dryRun=false
```

POST dùng multipart/form-data, part `file`; dryRun là query param, mặc định true. Không tự điền boundary.
GET thành công trả binary .xlsx với Content-Disposition attachment, không bọc BaseResponse; lỗi theo envelope chung.

| Sheet | Cột theo đúng thứ tự | Quy tắc |
|---|---|---|
| Instructions | Hướng dẫn và ví dụ bằng tiếng Việt | Không xử lý thành dữ liệu nhập |
| Organizations | orgType, code, name, parentCode, sortNo | orgType/code/name bắt buộc; code <=50, name <=255; sortNo nguyên 0..2147483647, trống = 0 |
| Teams | orgCode, code, name, description | orgCode/code/name bắt buộc; orgCode/code <=50, name <=255; description <=4000 ký tự |

Giữ nguyên tên sheet và header; hai sheet dữ liệu bắt buộc có mặt, được để trống một sheet.
Mẫu có header tô màu, cố định hàng đầu, định dạng text cho mã và dropdown UNIT/DEPARTMENT.
Ví dụ chỉ nằm trong Instructions để tránh nhập nhầm vào DB. Không dùng ID database, cột trạng thái,
user/password, thành viên hoặc role/grant trong tệp này. Tổ chức/nhóm tạo mới luôn active.

- UNIT được bỏ trống parentCode; DEPARTMENT phải có cha UNIT. Cha có thể có sẵn trong DB hoặc trong cùng tệp,
  kể cả nằm sau con. Không chấp nhận chu trình, tham chiếu đứt hoặc đường cha có node inactive.
- orgCode của Teams phải trỏ tới DEPARTMENT đang hoạt động hoặc DEPARTMENT mới của cùng tệp, không nhận UNIT.
  Nhóm được tạo sau tổ chức.
  Nếu Ban/Phòng đã có, không khai báo lại trong Organizations; dùng mã đó làm parentCode/orgCode. Chỉ nhập nhóm thì để Organizations trống.
- Mã so sánh không phân biệt hoa/thường, được trim; mã phải là text để giữ số 0 đầu.
- Chỉ tạo mới: mã tổ chức trùng DB/tệp hoặc mã nhóm trùng trong cùng tổ chức đều báo lỗi; không ghi đè/bỏ qua im lặng.
  API tạo nhóm đơn lẻ vẫn cho phép code rỗng, riêng import bắt buộc code để kiểm tra trùng rõ ràng.
- Global admin nhập cả Organizations/Teams. ADMIN ORG chỉ nhập Teams trong nhánh active của scope;
  Organizations phải trống. Dòng tham chiếu ngoài scope không làm lộ thông tin tổ chức ngoài quyền.
- Tối đa 5 MiB nén, 20 MiB nội dung ZIP, 100 ZIP entries, 1000 dòng dữ liệu tổng cộng; không đặt dữ liệu
  sau dòng Excel 1001 của mỗi sheet. Không nhận .xls/.xlsm, công thức, ô lỗi, ô gộp, sheet/cột lạ.
  Dòng trống bỏ qua, dòng ẩn vẫn xử lý. File staging được dọn sau xử lý; không lưu tệp nhập lâu dài.

Ví dụ data thành công của kiểm tra trước:

```json
{
  "dryRun": true,
  "organizationCount": 2,
  "teamCount": 3,
  "createdOrganizations": 0,
  "createdTeams": 0
}
```

Khi nhập thật thành công, dryRun=false và createdOrganizations/createdTeams bằng số dòng tương ứng.
Có lỗi: HTTP 200, status=ERROR, messageCode=ORGANIZATION-IMPORT_INVALID, data=null, không ghi dòng nào.
Tối đa 200 errors; field dùng `Sheet[dòng Excel].tênCột` với số dòng bắt đầu từ 1, header là dòng 1.
Lỗi file/header/giới hạn dùng field=file. Ví dụ phần errors:

```json
[
  {"field":"Organizations[3].code","code":"ALREADY_EXISTS","message":"Mã tổ chức đã được sử dụng."},
  {"field":"Teams[5].orgCode","code":"INVALID_REFERENCE","message":"Không tìm thấy tổ chức đang hoạt động trong phạm vi quản lý."}
]
```

Thiếu quyền trả 403/CORE-ACCESS_DENIED; không đăng nhập trả 401 như quy ước chung.
Lỗi DB/audit ngoài dự kiến vẫn là 500, không chuyển thành thông báo Excel sai; toàn bộ lô rollback.
Dry-run không khóa/ghi DB hoặc audit. Nhập thật parse file trước khi giữ lock; sau đó kiểm tra lại quyền,
tham chiếu và mã trùng dưới IdentityWriteLock, ghi dữ liệu và audit từng tổ chức/nhóm trong cùng transaction.
Kiểm tra trước thành công không giữ chỗ cho mã; nếu dữ liệu đổi, nhập thật có thể báo trùng/tham chiếu lại.

Luồng FE: tải mẫu → chọn tệp → kiểm tra → hiện số dòng/lỗi → xác nhận nhập. Đổi tệp phải xóa kết quả kiểm tra cũ;
khóa nút xác nhận khi đang gửi để tránh double-submit. Hiển thị mã node đang chọn để người dùng copy vào orgCode;
không tự thay mã tổ chức của các dòng trong tệp. Thành công tải lại tree và panel node đang chọn.
Import không tự tạo quyền quản trị hay gán nhân sự; các luồng đó dùng API Identity hiện có.

## 4. Bảng role/scope

Mỗi dòng là một grant, không phải một role độc lập và một scope rời nhau. Hiển thị roleCode/name, nhãn scope, org/team, người cấp, thời điểm cấp, thao tác thu hồi. `GET /roles` trả roleId/code/name/description/assignableScopes/capabilities/functions; `assignableScopes=[]` nghĩa là actor không được cấp role đó. Không hardcode roleId hoặc dựa vào tên tiếng Việt.

| Role | Scope được thiết kế | Ai được cấp |
|---|---|---|
| LEARNER | OWN | Global admin hoặc ADMIN ORG trong scope target |
| INSTRUCTOR | OWN/TEAM/ORG, GLOBAL khi cần | ADMIN ORG chỉ OWN/TEAM/ORG trong phạm vi quản lý; GLOBAL do global admin |
| REPORT_VIEWER | TEAM/ORG/GLOBAL | ADMIN ORG chỉ TEAM/ORG trong phạm vi quản lý; GLOBAL do global admin |
| ADMIN | ORG/GLOBAL | Chỉ SUPER_ADMIN GLOBAL |
| SUPER_ADMIN | GLOBAL | Chỉ SUPER_ADMIN GLOBAL; tài khoản gốc đầu tiên khởi tạo qua bootstrap/vận hành |

Trong các phần dưới, global admin chỉ tài khoản có quyền nghiệp vụ toàn hệ thống (ADMIN GLOBAL hoặc SUPER_ADMIN GLOBAL); quyền quản trị vai trò chỉ dành SUPER_ADMIN.

SUPER_ADMIN GLOBAL là quản trị gốc, quản lý cả nghiệp vụ toàn hệ thống và phân quyền quản trị. ADMIN GLOBAL quản trị nghiệp vụ toàn hệ thống nhưng không được cấp/thu hồi ADMIN hoặc SUPER_ADMIN và không được tác động tài khoản đang có vai trò quản trị. Không có bypass chỉ vì username hoặc tên role tùy ý. Các role ngoài danh mục policy có assignableScopes rỗng. Các role giảng viên/học viên/người xem báo cáo không được gọi API quản trị user/org/team.

- GLOBAL/OWN không gửi orgId/teamId. ORG yêu cầu orgId, không gửi teamId. TEAM yêu cầu teamId, orgId tùy chọn và nếu có phải khớp org thực tế của team.
- Chọn role trước, scope sau, rồi chọn target theo scope; clear target cũ khi đổi scope.
- Không tự gán hoặc thu hồi role của bản thân. Không cấp vượt scope; chỉ SUPER_ADMIN được cấp/thu hồi ADMIN/SUPER_ADMIN, kể cả qua initialRoleGrants khi tạo user. Grant đang active bị trùng trả HTTP 200/ERROR với mã IDENTITY-ROLE_GRANT_EXISTS; TEAM target giống nhau vẫn trùng dù lần này có/không gửi orgId.
- Thu hồi là deactivate grant, giữ lịch sử. Token cũ target mất hiệu lực. Quyền SUPER_ADMIN GLOBAL đang hoạt động cuối cùng được bảo vệ trong transaction với PostgreSQL advisory lock giữa các instance.
- Đổi scope bằng cấp grant mới rồi thu hồi grant cũ; đây là hai request riêng, FE hiển thị riêng từng kết quả, không báo atomic cả hai.
- `functions` là danh mục màn hình từ DB; `capabilities` chỉ mô tả các action quản trị mà role có thể có, còn phụ thuộc scope, target và field-level policy. ADMIN GLOBAL có thể tạo org/đổi status; ADMIN ORG không có hai thao tác này. SUPER_ADMIN có thêm capability ADMIN_ROLE_GRANT; ADMIN không có capability này. Không coi functions là quyền CREATE/UPDATE/DELETE.
- `/auth/me` giữ contract hiện tại `user,roles,functions,roleGrants,functionDetails,functionScopes`. Những scope trong roleGrants/functionScopes dùng field `type`; body POST role-grants dùng field `scope`.

## 5. Danh sách API: 21 core, 2 API đọc tổ chức và 2 API template/import

| # | Method và path dưới /api/v1 | Body/filter chính | data thành công |
|---|---|---|---|
| 1 | POST /auth/login | username,password | TokenResponse, HTTP 200 |
| 2 | POST /auth/logout | Không body | null, 200; thu hồi phiên hiện tại (sid) |
| 3 | GET /auth/me | Không body | MeResponse, 200 |
| 4 | PATCH /auth/me | fullName,email,positionName | MeResponse, 200 |
| 5 | PUT /auth/password | currentPassword,newPassword | null, 200; login lại |
| 6 | GET /users | q,roleCode,orgId,teamId,status,page,size,sort | PageResponse<UserSummary>, 200 |
| 7 | POST /users | CreateUserRequest ở phần 2 | UserDetail, 201 |
| 8 | GET /users/{userId} | Không body | UserDetail, 200 |
| 9 | PATCH /users/{userId} | fullName,email,positionName,orgId,teamId | UserDetail, 200 |
| 10 | PATCH /users/{userId}/status | `{ "status":"locked" }` | UserDetail, 200 |
| 11 | GET /roles | Không body | RoleOption[], 200 |
| 12 | POST /users/{userId}/role-grants | roleId,scope,orgId?,teamId? | `{ "grantId":101 }`, 201 |
| 13 | DELETE /users/{userId}/role-grants/{grantId} | Không body | null, 200 |
| 14 | GET /orgs | q,parentId,rootOnly,orgType,status,page,size,sort | PageResponse<OrganizationInfo>, 200 |
| 15 | POST /orgs | orgType,parentId?,code,name,sortNo (default 0) | OrganizationInfo, 201 |
| 16 | GET /orgs/{orgId} | Không body | OrganizationDetail, 200 |
| 17 | PATCH /orgs/{orgId} | code,name,sortNo,status (global-only) | OrganizationInfo, 200 |
| 18 | GET /orgs/{orgId}/teams | page,size,sort | PageResponse<TeamInfo>, 200 |
| 19 | POST /orgs/{orgId}/teams | code?,name,description? | TeamInfo, 201 |
| 20 | PATCH /teams/{teamId} | code,name,description | TeamInfo, 200 |
| 21 | DELETE /teams/{teamId} | Không body | null, 200 |
| O1 | GET /orgs/tree | parentId?,page,size (mặc định 20) | PageResponse<OrganizationTreeNode>, 200 |
| O2 | GET /orgs/{orgId}/contents | page,size (mặc định 6),sort | OrganizationContents, 200 |
| O3 | GET /orgs/import-template | Không body | Tệp .xlsx binary, 200 |
| O4 | POST /orgs/import | multipart file; query dryRun (mặc định true) | OrganizationImportResult, 200 |

Sort whitelist: users `id/username/fullName/createTime`; orgs `id/code/name/sortNo`; teams `id/code/name`. `q` tối đa 100 ký tự, tìm chuỗi literal không coi `%`/`_` là wildcard của người dùng. User query tìm userId/username/fullName/email; org query tìm code/name. Filters không được phép không mở rộng scope.

Không thêm `/members`, `/membership`, `/admins`, `/departments`, `/functions`, `/matrix`, `/metadata` hoặc API lookup riêng. API audit đọc I22 là tùy chọn chưa triển khai; backend đã ghi audit cho các thao tác thay đổi. Tab hoạt động tài khoản nên ẩn cho demo thay vì hiển thị dữ liệu học tập giả.

## 6. UI cần FE bổ sung/điều chỉnh

1. Form thêm user: mã cán bộ, username, mật khẩu tạm, nhiều role grants có scope/target; org/team là hai selector phụ thuộc nhau.
2. Login ghi rõ “Mã cán bộ hoặc username”; thêm luồng đổi mật khẩu tạm và màn đổi mật khẩu chủ động. Chưa hiển thị email login, quên mật khẩu gửi mail hoặc SSO button như tính năng đã hoạt động.
3. Profile: phân biệt field tự sửa với thông tin tổ chức/quyền và metadata nguồn readonly. LOCAL/SSO hiển thị theo authProvider.
4. User list/detail: ba trạng thái canonical; thao tác tạm dừng/kích hoạt riêng với khóa/mở. Không có hard-delete user. Role tab nhiều grant và nhãn scope rõ ràng.
5. Membership editor: cảnh báo thu hồi scoped grants khi chuyển; tải lại detail sau lưu; actor không tự chuyển hoặc tự sửa role.
6. Org screen: tải cây đơn vị/phòng bằng `/orgs/tree`, khi mở node truyền parentId; dùng hasChildren và totalPages để tải các cấp tiếp theo. Khi mở DEPARTMENT, tải các nhóm trực thuộc bằng `/orgs/{id}/teams` có phân trang; chọn nhóm gọi `/users?teamId=...` để xem thành viên theo scope, tìm kiếm và phân trang. Chọn node tổ chức gọi `/orgs/{id}/contents` để cập nhật panel và phân trang nhóm. Phân biệt node UNIT/DEPARTMENT với team; disable thao tác global-only đối với ADMIN ORG. Hiển thị loading/empty và bỏ qua response cũ nếu người dùng đã chuyển node khác.
7. Team card: preview 5 người + số lượng thật, “Xem tất cả” mở list `/users?teamId=...`; hiển thị lỗi ORGANIZATION-TEAM_IN_USE khi xóa nhóm còn tham chiếu.
8. Quyền: dựng selector theo assignableScopes, không single-role/adminScope; không cho UI tự đoán quyền theo positionName hoặc org của actor.
9. Xử lý loading/empty, HTTP 200/ERROR, validation và lỗi HTTP bảo mật/giao thức; hiển thị responseId để hỗ trợ tra cứu. Đổi role/status/membership/password có thể làm phiên target hết hiệu lực.

## 7. Khởi tạo database demo

Chạy duy nhất [learning_hub_identity.sql](../sql/learning_hub_identity.sql) một lần trên database/schema mới hoặc vừa tạo lại. File đã gồm đủ bảng/cột/index, danh mục vai trò/chức năng, cây tổ chức, nhóm và 7 tài khoản demo; không cần script upgrade hoặc gán root riêng. Giữ Flyway off và ddl-auto=validate; không dùng file này để nâng cấp database có dữ liệu cần giữ.

Tài khoản gốc super.admin (000001), mật khẩu demo Demo@123. Mọi user seed có mustChangePassword=false; /me trả quyền theo các grant còn hiệu lực, không yêu cầu đổi mật khẩu lần đầu. Danh sách đầy đủ và hướng dẫn tại [SQL README](../sql/README.md). Không cần cấu hình bootstrap Java khi dùng seed đã có root.

Swagger `/swagger-ui/index.html` là nguồn schema request/response chi tiết. Tài liệu này là quyết định UX/contract cho FE; không coi mockup hoặc tài liệu là bằng chứng kiểm thử đã chạy.

## 8. Bộ tài liệu và công cụ gửi FE

- File này: luồng tích hợp, phạm vi nghiệp vụ, form và quy tắc hiển thị.
- [API_CONTRACT.md](API_CONTRACT.md): BaseResponse, HTTP status, mã lỗi và validation dùng chung; đây là nguồn danh mục lỗi, không sao chép toàn bộ sang tài liệu Identity.
- Swagger `/swagger-ui/index.html` và OpenAPI `/v3/api-docs`: schema request/response chi tiết từ phiên bản backend đang triển khai.
- [Postman collection](../postman/corebanking-academy.postman_collection.json), [Local environment](../postman/local.postman_environment.json), [Dev environment](../postman/dev.postman_environment.json): mẫu 21 API core, 2 API đọc tổ chức và template/import, login tự bind accessToken vào environment đang chọn. Local dùng `http://localhost:8019`, Dev dùng `http://103.121.90.119:8019`; tự nhập mật khẩu vào environment, không commit credential/token đã điền.

Test theo từng luồng và chọn ID dữ liệu phù hợp; không chạy toàn collection như một kịch bản tự động dọn dữ liệu. Đổi mật khẩu/đăng xuất làm token cũ hết hiệu lực; xóa nhóm còn thành viên, grant hoặc tham chiếu Learning sẽ trả lỗi nghiệp vụ. Xem hướng dẫn Postman trong API_CONTRACT trước khi chạy.
