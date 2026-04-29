#!/usr/bin/env python3
"""
RAG 系统评估脚本
使用 RAGAS 框架评估 RAG 系统的核心指标

依赖安装:
    pip install ragas openai requests tqdm

运行:
    python scripts/rag_evaluator.py
"""

import json
import time
import requests
from dataclasses import dataclass, field
from typing import Optional
from tqdm import tqdm

# RAGAS 相关
try:
    import ragas
    from ragas import evaluate
    from ragas.metrics import (
        faithfulness,
        answer_relevancy,
        context_precision,
        context_recall,
    )
    RAGAS_AVAILABLE = True
    print(f"[OK] RAGAS 版本: {ragas.__version__}")
except ImportError as e:
    RAGAS_AVAILABLE = False
    print(f"[WARNING] RAGAS not installed. Run: pip install ragas")
    print(f"[DEBUG] Import error: {e}")

# ============== 配置 ==============
RAG_BASE_URL = "http://localhost:9090/api/ragsystem"
AUTH_ENDPOINT = f"{RAG_BASE_URL}/auth/login"
CHAT_ENDPOINT = f"{RAG_BASE_URL}/rag/v3/chat"
TRACE_ENDPOINT = f"{RAG_BASE_URL}/rag/traces/runs"

# 测试账号（需要根据实际情况修改）
TEST_USERNAME = "guest"
TEST_PASSWORD = "123456"

# 测试问题 - 按难度分级
# L1 基础: 直接匹配，答案明确，可直接从文档中找到
# L2 中级: 需要简单理解，答案需要一定整合
# L3 高级: 需要多步推理或综合多个知识点

TEST_QUESTIONS = [
    # ========== L1 基础问题 ==========
    # 特征：直接问，答案明确，不需要推理

    # 入职
    ("L1", "试用期有多久？"),
    ("L1", "工资什么时候发？"),
    ("L1", "公司地址是什么？"),

    # 考勤
    ("L1", "年假怎么计算？"),
    ("L1", "病假需要什么证明？"),

    # 财务
    ("L1", "报销多久能到账？"),
    ("L1", "出差怎么申请？"),

    # IT
    ("L1", "企业邮箱密码忘了怎么办？"),
    ("L1", "电脑坏了联系谁？"),

    # 安全
    ("L1", "收到钓鱼邮件怎么办？"),

    # ========== L2 中级问题 ==========
    # 特征：需要理解后回答，或涉及条件判断

    # 入职
    ("L2", "入职第一个月社保怎么缴纳？"),
    ("L2", "可以推迟入职吗？"),

    # 考勤
    ("L2", "忘记打卡怎么办？"),
    ("L2", "加班可以换工资吗？"),
    ("L2", "可以申请弹性工作吗？"),

    # 薪酬
    ("L2", "试用期工资怎么算？"),
    ("L2", "社保可以断缴吗？"),

    # 财务
    ("L2", "住宿超标可以报销吗？"),
    ("L2", "超期发票能报销吗？"),

    # ========== L3 高级问题 ==========
    # 特征：需要多步推理、综合多个知识点，或边界条件处理

    # 考勤
    ("L3", "入职满1年但不满3年，年假有多少天？"),
    ("L3", "试用期请年假会被批准吗？"),

    # 薪酬
    ("L3", "3月15日入职，当月工资怎么算？"),

    # 离职
    ("L3", "离职时年假没休完怎么办？"),
    ("L3", "培训协议服务期未满离职怎么处理？"),

    # 安全+IT
    ("L3", "发现信息泄露但不确定严重程度，应该先联系谁？"),
]

# 将问题提取为列表（用于迭代）
QUESTION_LIST = [q for _, q in TEST_QUESTIONS]

# 参考答案（与问题对应）
REFERENCE_ANSWERS = [
    # L1 基础
    "统一为3个月，劳动合同期限3年。",
    "每月15日发放上月工资。如遇节假日提前至前一个工作日。",
    "北京市朝阳区建国路88号SOHO现代城A座18层。",
    "入职满1年享5天，以后每满1年增加：满3年10天，满5年15天，满10年20天。",
    "3天以内需医院诊断证明；3天以上需诊断证明+病历；7天以上需完整病历+检查报告。",
    "审批通过后3个工作日内打款。",
    "出差前在OA系统提交出差申请，填写目的地、事由、时间，经直属上级审批。",
    "访问 password.company.com 自助找回，或联系IT部门重置。",
    "提交IT工单或拨打热线010-12345679，2小时内响应，紧急情况可电话催促。",
    "不要点击任何链接或附件，立即报告IT部门（it-emergency@company.com）。",

    # L2 中级
    "15日前入职当月参保，15日后入职次月参保。HR会办理相关手续。",
    "如有特殊情况可在收到offer后3天内联系HR申请延期，最长不超过2周。",
    "每月3次补卡机会，在OA系统申请补卡，说明原因即可。第4次起需总监审批。",
    "优先安排调休，无法调休时按加班费标准支付（工作日1.5倍、周末2倍、节假日3倍）。",
    "入职满3个月、绩效B及以上可申请。登录OA系统提交申请，选择上班时间（9:00或9:30）。",
    "试用期工资 = 转正工资 × 80%，餐补、交通补贴、通讯补贴正常发放。",
    "不可以，断缴影响看病报销、买房、落户等。如离职当月减员，新单位入职后续交。",
    "超标部分自理，特殊情况需提前申请并获批准。",
    "不能，发票有效期30天，跨期不予报销。",

    # L3 高级
    "入职满1年享5天，以后每满1年增加：满3年10天，所以是10天。",
    "试用期按转正天数折算，实际入职满1年才有年假，所以试用期没有年假。",
    "15日后入职按实际出勤计算，即(实际工作日/应工作日)×月工资。",
    "未休年假按日工资的3倍折算，随最后一个月工资发放。",
    "需按比例退还培训费用（服务期未满部分）。",
    "立即报告IT安全和HR部门，采取止损措施，配合调查。",
]


# 全局 session 和 token
session = requests.Session()
auth_token: Optional[str] = None


def login() -> bool:
    """登录获取 token"""
    global auth_token
    try:
        resp = session.post(AUTH_ENDPOINT, json={
            "username": TEST_USERNAME,
            "password": TEST_PASSWORD
        }, timeout=10)
        if resp.status_code == 200:
            data = resp.json()
            # success=true 或 code="0" 都表示成功
            if data.get("success") or str(data.get("code")) == "0":
                auth_token = data.get("data", {}).get("token")
                if auth_token:
                    # 设置后续请求的 header
                    session.headers.update({"Authorization": auth_token})
                    print(f"[OK] 登录成功, token: {auth_token[:20]}...")
                    return True
        print(f"[ERROR] 登录失败: {resp.text}")
        return False
    except Exception as e:
        print(f"[ERROR] 登录异常: {e}")
        return False


@dataclass
class EvaluationResult:
    """单次评估结果"""
    level: str  # L1/L2/L3
    question: str
    answer: str
    reference: str
    retrieved_contexts: list[str]
    trace_id: Optional[str] = None
    duration_ms: Optional[int] = None
    error: Optional[str] = None

    # RAGAS 评估指标
    faithfulness_score: Optional[float] = None
    answer_relevancy_score: Optional[float] = None
    context_precision_score: Optional[float] = None
    context_recall_score: Optional[float] = None

    def to_dict(self):
        return {
            "level": self.level,
            "question": self.question,
            "answer": self.answer[:200] + "..." if len(self.answer) > 200 else self.answer,
            "reference": self.reference[:200] + "..." if len(self.reference) > 200 else self.reference,
            "contexts_count": len(self.retrieved_contexts),
            "trace_id": self.trace_id,
            "duration_ms": self.duration_ms,
            "error": self.error,
            "metrics": {
                "faithfulness": self.faithfulness_score,
                "answer_relevancy": self.answer_relevancy_score,
                "context_precision": self.context_precision_score,
                "context_recall": self.context_recall_score,
            }
        }


def call_rag_chat(question: str, timeout: int = 120) -> tuple[str, Optional[str], int]:
    """
    调用 RAG 聊天接口

    Returns:
        (answer, trace_id, duration_ms)
    """
    start_time = time.time()
    try:
        response = session.get(
            CHAT_ENDPOINT,
            params={"question": question},
            timeout=timeout,
            stream=True
        )

        full_answer = ""
        trace_id = None

        # SSE 流式响应处理
        all_data = []
        for line in response.iter_lines():
            if not line:
                continue
            try:
                line_str = line.decode('utf-8') if isinstance(line, bytes) else line
                all_data.append(line_str)
            except Exception:
                continue

        # 解析 SSE
        full_answer = ""
        trace_id = None
        current_event = None

        for line_str in all_data:
            if line_str.startswith('event:'):
                current_event = line_str[6:].strip()
                continue

            if line_str.startswith('data:'):
                data = line_str[5:].strip()
                if data == '[DONE]':
                    break
                try:
                    event = json.loads(data)
                    # meta 事件包含 traceId
                    if current_event == 'meta' and 'traceId' in event:
                        trace_id = event['traceId']
                    # message 事件的 response 类型包含 delta
                    if current_event == 'message' and event.get('type') == 'response':
                        if 'delta' in event:
                            full_answer += event['delta']
                    if 'error' in event:
                        print(f"[SERVER ERROR] {event.get('error')}")
                except json.JSONDecodeError:
                    continue

        duration_ms = int((time.time() - start_time) * 1000)
        return full_answer, trace_id, duration_ms

    except requests.exceptions.Timeout:
        duration_ms = int((time.time() - start_time) * 1000)
        return "", None, duration_ms
    except Exception as e:
        duration_ms = int((time.time() - start_time) * 1000)
        return "", None, duration_ms


def get_retrieved_contexts(trace_id: str) -> list[str]:
    """
    从 Trace API 获取检索到的上下文
    """
    try:
        response = session.get(f"{TRACE_ENDPOINT}/{trace_id}", timeout=10)
        if response.status_code == 200:
            data = response.json()
            if data.get("code") == 200:
                nodes = data.get("data", {}).get("nodes", [])
                contexts = []
                for node in nodes:
                    if node.get("nodeType") == "RETRIEVE":
                        # 尝试从节点数据中提取检索到的 chunk
                        # 这里需要根据实际返回结构进行调整
                        pass
                return contexts
    except Exception as e:
        print(f"[WARN] 获取 trace 失败: {e}")
    return []


def extract_contexts_from_trace(trace_id: str) -> list[str]:
    """
    从 trace 详情中提取检索到的上下文内容

    这需要根据实际返回的 node 数据结构来进行解析
    """
    try:
        resp = requests.get(f"{TRACE_ENDPOINT}/{trace_id}", timeout=10)
        if resp.status_code != 200:
            return []

        result = resp.json()
        if result.get("code") != 200:
            return []

        nodes = result.get("data", {}).get("nodes", [])
        contexts = []

        for node in nodes:
            node_name = node.get("nodeName", "")
            # 查找检索节点
            if "retrieval" in node_name.lower() or "retrieve" in node_name.lower():
                # 这里需要根据实际数据结构提取
                # 通常 context 信息会在 node 的某个字段中
                pass

        return contexts
    except Exception as e:
        print(f"[WARN] 提取上下文失败: {e}")
        return []


def evaluate_with_ragas(results: list[EvaluationResult]) -> list[EvaluationResult]:
    """
    使用 RAGAS 评估结果

    注意: RAGAS 的 evaluate 需要每个 sample 包含 user_input, reference, retrieved_contexts, response
    这里我们用单轮对话评估
    """
    if not RAGAS_AVAILABLE:
        print("[WARNING] RAGAS not available, skipping metric calculation")
        return results

    try:
        # 构建数据集
        from ragas.dataset_schema import SingleTurnSample
        from datasets import Dataset

        dataset_dict = {
            "user_input": [],
            "reference": [],
            "retrieved_contexts": [],
            "response": [],
        }

        for r in results:
            if r.error:
                continue
            dataset_dict["user_input"].append(r.question)
            dataset_dict["reference"].append(r.reference)
            dataset_dict["retrieved_contexts"].append(r.retrieved_contexts)
            dataset_dict["response"].append(r.answer)

        if len(dataset_dict["user_input"]) == 0:
            print("[WARN] 没有有效结果进行评估")
            return results

        dataset = Dataset.from_dict(dataset_dict)

        # 执行评估
        print("\n[RAGAS] 开始评估...")
        score = evaluate(
            dataset,
            metrics=[
                faithfulness,
                answer_relevancy,
                context_precision,
                context_recall,
            ]
        )

        # 解析结果并更新到 results
        scores_dict = score.to_pandas().to_dict()

        for i, r in enumerate(results):
            if r.error:
                continue
            try:
                r.faithfulness_score = scores_dict["faithfulness"][i]
                r.answer_relevancy_score = scores_dict["answer_relevancy"][i]
                r.context_precision_score = scores_dict["context_precision"][i]
                r.context_recall_score = scores_dict["context_recall"][i]
            except (KeyError, IndexError):
                pass

        return results

    except Exception as e:
        print(f"[ERROR] RAGAS 评估失败: {e}")
        import traceback
        traceback.print_exc()
        return results


def print_results_summary(results: list[EvaluationResult]):
    """打印评估结果摘要"""
    print("\n" + "=" * 80)
    print("RAG 评估结果摘要")
    print("=" * 80)

    valid_results = [r for r in results if not r.error]
    failed_results = [r for r in results if r.error]

    print(f"\n总测试数: {len(results)}")
    print(f"成功: {len(valid_results)}")
    print(f"失败: {len(failed_results)}")

    # 按级别统计
    level_stats = {}
    for level in ["L1", "L2", "L3"]:
        level_results = [r for r in results if r.level == level]
        level_valid = [r for r in level_results if not r.error]
        level_stats[level] = {
            "total": len(level_results),
            "success": len(level_valid),
            "avg_duration": sum(r.duration_ms for r in level_valid) / len(level_valid) if level_valid else 0
        }

    print("\n分级别统计:")
    for level, stats in level_stats.items():
        level_name = {"L1": "基础", "L2": "中级", "L3": "高级"}[level]
        print(f"  {level} ({level_name}): {stats['success']}/{stats['total']} 成功, "
              f"平均耗时 {stats['avg_duration']:.0f}ms")

    # 计算平均响应时间
    if valid_results:
        avg_duration = sum(r.duration_ms for r in valid_results) / len(valid_results)
        print(f"总平均响应时间: {avg_duration:.0f}ms")

        # 打印指标
        metrics = ["faithfulness_score", "answer_relevancy_score",
                   "context_precision_score", "context_recall_score"]
        metric_names = ["Faithfulness", "Answer Relevancy", "Context Precision", "Context Recall"]

        print("\nRAGAS 指标 (总体):")
        for metric, name in zip(metrics, metric_names):
            values = [getattr(r, metric) for r in valid_results if getattr(r, metric) is not None]
            if values:
                avg = sum(values) / len(values)
                print(f"  {name}: {avg:.4f} (有效样本: {len(values)})")

        # 分级别打印指标
        for level in ["L1", "L2", "L3"]:
            level_results = [r for r in valid_results if r.level == level]
            has_metrics = any(getattr(r, m) is not None for r in level_results for m in metrics)
            if not has_metrics:
                continue

            print(f"\nRAGAS 指标 ({level} - {level} 级):")
            for metric, name in zip(metrics, metric_names):
                values = [getattr(r, metric) for r in level_results if getattr(r, metric) is not None]
                if values:
                    avg = sum(values) / len(values)
                    print(f"  {name}: {avg:.4f}")

    # 打印详细结果
    print("\n" + "-" * 80)
    print("详细结果:")
    print("-" * 80)

    current_level = None
    for i, r in enumerate(results):
        # 级别分隔
        if r.level != current_level:
            current_level = r.level
            level_name = {"L1": "=== L1 基础问题 ===", "L2": "=== L2 中级问题 ===", "L3": "=== L3 高级问题 ==="}[current_level]
            print(f"\n{level_name}")

        status = "✓" if not r.error else "✗"
        print(f"\n  [{i+1}] {status} {r.question}")
        if r.error:
            print(f"      错误: {r.error}")
        else:
            if r.duration_ms:
                print(f"      耗时: {r.duration_ms}ms")
            print(f"      回答: {r.answer[:100]}...")
            if any([r.faithfulness_score, r.answer_relevancy_score, r.context_precision_score, r.context_recall_score]):
                print(f"      指标: f={r.faithfulness_score:.3f} ar={r.answer_relevancy_score:.3f} "
                      f"cp={r.context_precision_score:.3f} cr={r.context_recall_score:.3f}")


def save_results(results: list[EvaluationResult], filepath: str = "rag_evaluation_results.json"):
    """保存结果到 JSON 文件"""
    output = {
        "summary": {
            "total": len(results),
            "success": len([r for r in results if not r.error]),
            "failed": len([r for r in results if r.error]),
            "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        },
        "results": [r.to_dict() for r in results]
    }

    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print(f"\n结果已保存到: {filepath}")


def main():
    print("=" * 80)
    print("RAG 系统评估脚本")
    print("=" * 80)
    print(f"RAG 地址: {CHAT_ENDPOINT}")
    print(f"测试问题数: {len(TEST_QUESTIONS)} (L1={len([t for t in TEST_QUESTIONS if t[0]=='L1'])}, "
          f"L2={len([t for t in TEST_QUESTIONS if t[0]=='L2'])}, "
          f"L3={len([t for t in TEST_QUESTIONS if t[0]=='L3'])})")
    print(f"RAGAS 可用: {RAGAS_AVAILABLE}")
    print("=" * 80)

    # 1. 登录获取 token
    if not login():
        print("[ERROR] 登录失败，无法继续评估")
        return

    # 2. 检查服务是否可用（带 token）
    try:
        resp = session.get(CHAT_ENDPOINT, params={"question": "test"}, timeout=5)
        print(f"[OK] RAG 服务可用, 状态码: {resp.status_code}")
    except Exception as e:
        print(f"[ERROR] RAG 服务不可用: {e}")
        print("请确保 RAG 服务正在运行 (localhost:9090)")
        return

    results = []

    print("\n开始评估...")
    for i, (level, question) in enumerate(tqdm(TEST_QUESTIONS, desc="评估进度")):
        reference = REFERENCE_ANSWERS[i] if i < len(REFERENCE_ANSWERS) else ""

        try:
            answer, trace_id, duration_ms = call_rag_chat(question)

            if not answer:
                results.append(EvaluationResult(
                    level=level,
                    question=question,
                    answer="",
                    reference=reference,
                    retrieved_contexts=[],
                    error="Empty response"
                ))
                continue

            # 获取检索上下文
            contexts = extract_contexts_from_trace(trace_id) if trace_id else []

            results.append(EvaluationResult(
                level=level,
                question=question,
                answer=answer,
                reference=reference,
                retrieved_contexts=contexts,
                trace_id=trace_id,
                duration_ms=duration_ms
            ))

        except Exception as e:
            results.append(EvaluationResult(
                level=level,
                question=question,
                answer="",
                reference=reference,
                retrieved_contexts=[],
                error=str(e)
            ))

        # 等待 rate limit lease 过期（lease=30s，多等5秒安全余量）
        time.sleep(35)

    # RAGAS 评估
    if RAGAS_AVAILABLE:
        results = evaluate_with_ragas(results)

    # 打印结果
    print_results_summary(results)

    # 保存结果
    save_results(results)

    print("\n评估完成!")


if __name__ == "__main__":
    main()