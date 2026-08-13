-- 静态种子数据：先清空再插入，保证每次启动幂等（data.sql 在每次启动时都会执行）
DELETE FROM learning_resources;

INSERT INTO learning_resources (title, description, url) VALUES
('后端入门开发手册', '涵盖 java/SpringBoot/SpringCloud 核心基础，适合零基础快速入门', NULL),
('Agent 开发实战指南', '从 0 到 1 搭建智能体应用，包含完整案例与可运行代码', NULL),
('大模型应用开发教程', '基于大模型的应用开发指南，涵盖 API 调用、数据处理与优化策略', NULL),
('前端开发进阶手册', '深入理解 Vue3、React、TypeScript 等前端技术栈，提升开发效率', NULL);

-- ============================================================
-- 能力培养条目（首页"我们培养的能力"），幂等种子
-- ============================================================
MERGE INTO service_item (id, title, description, icon, sort_order, status) KEY(id) VALUES
(1, 'AI智能应用开发', '使用前沿技术栈，构建高性能、响应式的智能化应用。', '🌐', 1, 'published'),
(2, '大模型学术论文研究', '聚焦大语言模型前沿方向，开展学术论文撰写与发表。', '🔬', 2, 'published'),
(3, 'Agent开发', '基于大模型开发智能体，支持自主规划、工具调用与任务落地。', '🤖', 3, 'published');

-- ============================================================
-- 作品，幂等种子
-- ============================================================
MERGE INTO work (id, slug, title, category, summary, content, external_link, featured, sort_order, status, view_count, icon, cover_class, tag_type, authors, date_label, created_at, updated_at) KEY(id) VALUES
(1, 'acm-icpc-gold', 'ACM-ICPC 亚洲区域赛金牌', '竞赛',
'在第 48 届 ACM-ICPC 亚洲区域赛中，团队凭借出色的算法设计与协作能力，从 300+ 支队伍中脱颖而出，斩获金牌。',
'ICPC（国际大学生程序设计竞赛）是全球最具影响力的大学生算法竞赛。本次参赛我们经历了数月的系统训练，重点攻克图论、动态规划与计算几何等核心方向。
赛场上五小时的高强度比拼，考验的不仅是算法功底，更是团队分工与临场决策能力。最终以稳定的发挥拿下金牌，为工作室的算法实力写下有力注脚。
如果你对算法竞赛感兴趣，欢迎通过「加入我们」与我们交流。',
NULL, TRUE, 1, 'published', 0, '🏆', 'bg-gradient-to-br from-amber-100 to-orange-100', 'award', '张三、李四、王五', '2025-12', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(2, 'sci-super-resolution', 'SCI 论文：基于深度学习的图像超分辨率重建', '论文',
'提出一种新型注意力机制网络架构，在 Set5、Set14 等基准数据集上 PSNR 指标达到 SOTA，被 IEEE TIP 期刊录用。',
'图像超分辨率重建是计算机视觉的经典难题。本工作提出了一种结合通道注意力与空间注意力的新型网络架构，在保持计算量可控的同时显著提升重建质量。
在 Set5、Set14、BSD100 等多个公开基准上的实验表明，所提方法在 PSNR 与 SSIM 指标上均达到当时最优（SOTA）水平，论文被 IEEE TIP 期刊录用。
本成果是大模型学术研究方向的重要积累，也为后续视觉大模型工作奠定了基础。',
NULL, TRUE, 2, 'published', 0, '📄', 'bg-gradient-to-br from-emerald-100 to-teal-100', 'paper', '赵六、孙七 等', '2026-03', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(3, 'design-competition-first', '中国大学生计算机设计大赛一等奖', '竞赛',
'作品「智行」—— 基于 Vue 3 与 WebSocket 的实时协作白板应用，荣获交互设计组全国一等奖。',
'「智行」是一款面向远程协作场景的实时白板应用：基于 Vue 3 构建界面，通过 WebSocket 实现多人实时协同编辑，支持画笔、贴纸、文本等丰富元素。
项目从交互设计、技术选型到性能优化均由团队独立完成，经过多轮评审与现场答辩，最终荣获中国大学生计算机设计大赛交互设计组全国一等奖。
该作品完整展示了工作室在前端工程与实时应用领域的能力。',
NULL, TRUE, 3, 'published', 0, '🥇', 'bg-gradient-to-br from-indigo-100 to-violet-100', 'award', '周八、吴九、郑十', '2025-08', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
(4, 'llm-code-generation', 'SCI 论文：大语言模型在代码生成中的应用研究', '论文',
'探索 GPT 系列模型在自动化代码生成任务中的表现，提出基于检索增强的微调策略，被 ACM TOSEM 期刊收录。',
'代码生成是大语言模型最具落地价值的应用方向之一。本工作系统评估了主流大模型在代码补全、程序合成与缺陷修复任务上的表现差异。
针对大模型在长上下文代码场景中的幻觉问题，我们提出了一种基于检索增强的微调策略，通过引入相似代码片段显著提升生成准确率。
研究成果被 ACM TOSEM 期刊收录，相关方法已应用于工作室的 Agent 开发实践。',
NULL, FALSE, 4, 'published', 0, '', 'bg-gradient-to-br from-rose-100 to-pink-100', 'paper', '陈一、林二 等', '2026-01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
