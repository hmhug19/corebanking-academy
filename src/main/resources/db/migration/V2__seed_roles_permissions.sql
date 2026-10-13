insert into roles(code,name) values
 ('LEARNER','Học viên'),('INSTRUCTOR','Giảng viên'),('ADMIN','Quản trị hệ thống'),('REPORT_VIEWER','Người xem báo cáo');
insert into permissions(code,resource,action) values
 ('course.view','course','view'),('course.learn','course','learn'),('course.create','course','create'),
 ('course.update','course','update'),('course.publish','course','publish'),('course.assign','course','assign'),
 ('lesson.create','lesson','create'),('lesson.update','lesson','update'),
 ('question.create','question','create'),('question.update','question','update'),
 ('quiz.take','quiz','take'),('quiz.result.view_own','quiz.result','view_own'),
 ('report.view','report','view'),('report.export','report','export'),
 ('user.manage','user','manage'),('organization.manage','organization','manage'),('role.assign','role','assign');
insert into role_permissions(role_id,permission_id)
select r.id,p.id from roles r cross join permissions p where
 (r.code='LEARNER' and p.code in ('course.view','course.learn','quiz.take','quiz.result.view_own')) or
 (r.code='INSTRUCTOR' and p.code in ('course.view','course.create','course.update','course.publish','course.assign','lesson.create','lesson.update','question.create','question.update')) or
 (r.code='REPORT_VIEWER' and p.code in ('report.view','report.export')) or
 (r.code='ADMIN');
-- Synthetic test organization only. Real org data should be imported separately.
insert into organizations(code,name,type) values('UNIT_PAYMENT','Core Payment','UNIT'),('UNIT_ACCOUNTING','Core Accounting','UNIT');
